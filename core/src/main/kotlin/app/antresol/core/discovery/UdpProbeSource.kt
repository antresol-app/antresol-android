package app.antresol.core.discovery

import app.antresol.core.api.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Something that reports servers it sees while collected (UDP probe, mDNS browse). */
fun interface DiscoverySource {
    fun sightings(): Flow<ServerSighting>
}

/** The confirmed servers on the network while collected ([ServerDiscovery], or a fake in UI tests). */
fun interface ServerFinder {
    fun states(): Flow<DiscoveryState>

    companion object {
        /** Finds nothing (previews, tests). */
        val None = ServerFinder { kotlinx.coroutines.flow.flowOf(DiscoveryState(emptyList(), searching = false)) }
    }
}

/**
 * The UDP probe of the contract (§1.3): sends `antresol-discover` [sends] times [gapMs] apart to
 * `255.255.255.255`, every interface's subnet broadcast and [unicastHosts] (a saved server, which
 * also works through Docker's `8420:8420/udp` mapping), and collects answers for [listenMs]. The
 * round repeats every [repeatMs] while collected, so a server started later still shows up.
 * Traffic stays on the local network: the server only answers private/link-local sources.
 */
class UdpProbeSource(
    private val port: Int = DiscoveryProtocol.PORT,
    private val unicastHosts: suspend () -> List<String> = { emptyList() },
    private val broadcastTargets: () -> List<InetAddress> = ::defaultBroadcastTargets,
    private val sends: Int = 3,
    private val gapMs: Long = 300,
    private val listenMs: Long = 2_000,
    private val repeatMs: Long = 10_000,
) : DiscoverySource {
    override fun sightings(): Flow<ServerSighting> = flow {
        DatagramSocket(null as java.net.SocketAddress?).use { socket ->
            socket.broadcast = true
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = RECEIVE_SLICE_MS
            val buffer = ByteArray(DiscoveryProtocol.MAX_ANSWER_BYTES + 512)
            while (true) {
                val targets = targets()
                val started = System.nanoTime()
                var sent = 0
                while (elapsedMs(started) < listenMs) {
                    currentCoroutineContext().ensureActive()
                    if (sent < sends && elapsedMs(started) >= sent * gapMs) {
                        targets.forEach { target -> send(socket, target) }
                        sent++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    DiscoveryProtocol.parseUdpAnswer(packet.data.copyOf(packet.length), packet.length, packet.address)
                        ?.let { emit(it) }
                }
                delay(repeatMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun targets(): List<InetSocketAddress> {
        // Only private IPv4 literals: no DNS lookup, and no probe ever leaves the local network.
        val unicast = attempt { unicastHosts() }.orEmpty()
            .filter { IPV4_LITERAL.matches(it) }
            .mapNotNull { host -> runCatching { InetAddress.getByName(host) }.getOrNull() }
            .filter { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }
        val broadcast = runCatching { broadcastTargets() }.getOrDefault(emptyList())
        return (broadcast + unicast).distinct().map { InetSocketAddress(it, port) }
    }

    private fun send(socket: DatagramSocket, target: InetSocketAddress) {
        try {
            socket.send(DatagramPacket(DiscoveryProtocol.PROBE, DiscoveryProtocol.PROBE.size, target))
        } catch (_: IOException) {
            // Some networks refuse 255.255.255.255 (or a stale interface): the other targets still go out.
        } catch (_: SecurityException) {
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        private const val RECEIVE_SLICE_MS = 100
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** `255.255.255.255` plus the IPv4 broadcast address of every interface that is up (not loopback). */
        fun defaultBroadcastTargets(): List<InetAddress> {
            val subnets = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { it.interfaceAddresses.orEmpty() }
                    .filter { it.address is Inet4Address }
                    .mapNotNull { it.broadcast ?: subnetBroadcast(it.address as Inet4Address, it.networkPrefixLength.toInt()) }
            }.getOrDefault(emptyList())
            return (listOf(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))) + subnets).distinct()
        }

        /** The broadcast address of [address]/[prefixLength] (null for /31, /32 or a bad prefix). */
        fun subnetBroadcast(address: Inet4Address, prefixLength: Int): InetAddress? {
            if (prefixLength !in 1..30) return null
            val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val hostMask = (1L shl (32 - prefixLength)) - 1
            val broadcast = ip or hostMask
            val bytes = ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() }
            return InetAddress.getByAddress(bytes)
        }
    }
}
