package app.antresol.ui.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import app.antresol.core.discovery.DiscoveryProtocol
import app.antresol.core.discovery.DiscoverySource
import app.antresol.core.discovery.DiscoveryState
import app.antresol.core.discovery.ServerDiscovery
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.discovery.ServerSighting
import app.antresol.core.discovery.UdpProbeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Antresol servers on the home network, for the "Connect to your server" screens: the UDP probe
 * (primary: it also works through Docker's port mapping) and mDNS via [NsdManager] (secondary,
 * flaky on some TV boxes), merged by server id and confirmed with `/api/health`.
 *
 * A Wi-Fi [WifiManager.MulticastLock] is held while collecting (many devices drop multicast and
 * broadcast answers without it). No location permission is involved. [unicastHosts]: private IPv4
 * addresses to probe directly as well (the last server used).
 */
class LanDiscovery(
    context: Context,
    private val unicastHosts: suspend () -> List<String> = { emptyList() },
) : ServerFinder {
    private val app = context.applicationContext

    override fun states(): Flow<DiscoveryState> {
        var lock: WifiManager.MulticastLock? = null
        return ServerDiscovery(
            sources = listOf(UdpProbeSource(unicastHosts = unicastHosts), NsdSource(app)),
            health = ServerDiscovery.quickHealthCheck(),
        ).states()
            .onStart { lock = acquireMulticastLock() }
            .onCompletion { runCatching { lock?.release() } }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        app.getSystemService(WifiManager::class.java)?.createMulticastLock("antresol-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * DNS-SD browse of `_antresol._tcp` with [NsdManager]: every found service is resolved (one at a
 * time: before Android 14 a second resolve fails with FAILURE_ALREADY_ACTIVE) and its TXT record
 * and IPv4 address reported. Any NsdManager failure just ends this source; the UDP probe goes on.
 */
private class NsdSource(private val context: Context) : DiscoverySource {
    override fun sightings(): Flow<ServerSighting> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            awaitClose()
            return@callbackFlow
        }
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        val started = try {
            nsd.discoverServices(DiscoveryProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        launch {
            for (service in found) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(nsd, service) } ?: continue
                @Suppress("DEPRECATION")
                val hosts: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
                    resolved.hostAddresses
                } else {
                    listOfNotNull(resolved.host)
                }
                DiscoveryProtocol.parseMdns(resolved.attributes.orEmpty(), hosts, resolved.port)?.let { send(it) }
            }
        }
        awaitClose {
            found.close()
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService works on every API level; the 34+ callback API adds nothing needed here.
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    },
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
