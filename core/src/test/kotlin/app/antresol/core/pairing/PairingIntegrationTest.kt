package app.antresol.core.pairing

import app.antresol.core.api.AntresolClient
import app.antresol.core.discovery.ServerDiscovery
import app.antresol.core.discovery.UdpProbeSource
import app.antresol.core.model.DeviceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Discovery and QR sign-in against a real server (contract «LAN discovery and TV sign-in by QR»).
 * Runs only with `ANTRESOL_PAIRING_E2E=<base URL>` (a server on this machine), plus
 * `ANTRESOL_PAIRING_E2E_UDP` (its `VAULT_DISCOVERY_PORT`, default 8420) and
 * `ANTRESOL_PAIRING_E2E_PASSWORD` (the password of profile 1, the admin). It signs a fake TV in
 * as the admin, approves from a second "phone" session, and signs both out at the end.
 */
class PairingIntegrationTest {
    private val base = System.getenv("ANTRESOL_PAIRING_E2E")?.trimEnd('/')
    private val udpPort = System.getenv("ANTRESOL_PAIRING_E2E_UDP")?.toIntOrNull() ?: 8420
    private val password = System.getenv("ANTRESOL_PAIRING_E2E_PASSWORD") ?: "admin123"

    @Test
    fun discoveredByUdpProbeThenTvSignsInByApproval() = runBlocking {
        assumeTrue("set ANTRESOL_PAIRING_E2E to run", !base.isNullOrBlank())
        val baseUrl = base!!
        val serverId = AntresolClient(baseUrl).health().serverId

        // Discovery: a unicast probe to the server's host (what a phone does for a saved server).
        val host = baseUrl.toHttpUrl().host
        val discovery = ServerDiscovery(
            sources = listOf(UdpProbeSource(port = udpPort, unicastHosts = { listOf(host) }, broadcastTargets = { emptyList() })),
            health = ServerDiscovery.quickHealthCheck(),
        )
        val found = withTimeout(10_000) { discovery.states().first { it.servers.isNotEmpty() } }.servers.single()
        assertEquals(serverId, found.id)
        assertTrue(found.baseUrl, found.baseUrl.startsWith("http://$host:"))

        // The phone: a normal password sign-in of the admin.
        val phone = AntresolClient(found.baseUrl)
        val admin = phone.signIn(1, password, DeviceInfo("e2e-phone-device-01", "E2E Phone", "Test", "1"))

        // The TV: one PairingFlow (one poller, as the contract requires); its states go to a channel.
        val tv = AntresolClient(found.baseUrl)
        val tvDevice = DeviceInfo("e2e-tv-device-0001", "E2E Living Room", "Android TV", "1")
        val states = Channel<PairingState>(Channel.UNLIMITED)
        val collector = launch { PairingFlow(tv.pairingApi(), { tvDevice }).states().collect { states.send(it) } }
        try {
            val pending = withTimeout(10_000) { states.receiveAsFlow().filterIsInstance<PairingState.Pending>().first() }
            assertEquals(serverId, pending.serverId)
            assertTrue(pending.url, pending.url.contains("/pair?code=${pending.code}"))
            assertEquals(pending.code, PairLink.parse(pending.url)!!.code)

            // The phone scans it: lookup → approve as itself (userId 0).
            val lookup = phone.pairLookup(pending.code.lowercase())
            assertEquals("E2E Living Room", lookup.device.name)
            assertEquals(false, lookup.remote)
            assertEquals(admin.user.id, phone.pairApprove(pending.code, 0).userId)

            val approved = withTimeout(15_000) { states.receiveAsFlow().filterIsInstance<PairingState.Approved>().first() }
            assertEquals(admin.user.id, approved.user.id)

            // The token is a normal device token: /api/me works and the TV is in «Устройства».
            tv.token = approved.token
            assertEquals(admin.user.id, tv.me().id)
            assertTrue(phone.devices().items.any { it.name == "E2E Living Room" })
            tv.logout()
        } finally {
            collector.cancel()
            phone.logout()
        }
    }
}
