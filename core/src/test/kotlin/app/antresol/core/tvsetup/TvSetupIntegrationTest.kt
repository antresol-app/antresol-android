package app.antresol.core.tvsetup

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairLookup
import app.antresol.core.pairing.PairingFlow
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.pairing.PairingState
import app.antresol.core.pairing.pairingApi
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * «TV setup by phone» end to end against a real server (`feat/lan-pairing`): the TV's setup
 * listener (on loopback) ← the phone's `POST /setup` → the TV checks the server and starts pairing
 * → the phone looks the code up and approves it on its own server → the TV's pairing flow picks
 * the token up → the TV is signed in. Runs only with `ANTRESOL_TVSETUP_E2E=<base URL>` (plus
 * `ANTRESOL_PAIRING_E2E_PASSWORD`, the password of profile 1, the admin; default `admin123`).
 */
class TvSetupIntegrationTest {
    private val base = System.getenv("ANTRESOL_TVSETUP_E2E")?.trimEnd('/')
    private val password = System.getenv("ANTRESOL_PAIRING_E2E_PASSWORD") ?: "admin123"

    @Test
    fun phoneSetsUpATvThatHasNoServer() = runBlocking {
        assumeTrue("set ANTRESOL_TVSETUP_E2E to run", !base.isNullOrBlank())
        val baseUrl = base!!
        val serverId = AntresolClient(baseUrl).health().serverId

        // The TV: no server yet; its first-run screen opens the listener.
        val tv = SessionManager(
            store = InMemorySessionStore(deviceId = "e2e-tvsetup-tv-0001"),
            deviceInfo = { DeviceInfo(it, "E2E Setup TV", "Android TV", "1") },
            language = { "en" },
            scope = CoroutineScope(Dispatchers.IO),
        )
        tv.restore()
        assertEquals(AuthState.NoServer, tv.state.value)
        val ticket = PairingTicket()
        val listener = TvSetupListener(TvSetupCoordinator(tv, ticket))
        var finished = false
        listener.onFinished = { finished = true }
        val qr = listener.start(InetAddress.getLoopbackAddress()).toString()

        // The phone: signed in as the admin, scans the TV's QR.
        val phone = AntresolClient(baseUrl)
        val admin = phone.signIn(1, password, DeviceInfo("e2e-tvsetup-phone-01", "E2E Phone", "Test", "1"))
        try {
            val scanned = ScannedTvCode.parse(qr, allowLoopback = true) as ScannedTvCode.Setup
            val approver = object : TvSetupApprover {
                override suspend fun lookup(code: String): PairLookup = phone.pairLookup(code).also {
                    assertEquals("E2E Setup TV", it.device.name)
                    assertEquals(false, it.remote)
                }

                override suspend fun approve(code: String, userId: Long): PairApproved = phone.pairApprove(code, userId)
            }
            val answer = sendTvSetup(TvSetupSender()::send, scanned.link, baseUrl, serverId)
            approveTvCode(approver, answer.code, 0)
            assertEquals(serverId, answer.sid)
            assertEquals(baseUrl, answer.server)
            assertEquals("E2E Setup TV", answer.device)

            // The TV saved the server like a typed address and closed the listener.
            withTimeout(5_000) { while (!finished) kotlinx.coroutines.delay(20) }
            assertTrue(listener.stopped)
            assertEquals(AuthState.ChooseProfile(baseUrl, serverId), tv.state.value)

            // Its pairing QR screen polls the handed-over code (no new start) and gets the token.
            val started = ticket.take(serverId)!!
            assertEquals(answer.code, started.start.code)
            val approved = withTimeout(15_000) {
                PairingFlow(tv.requireClient().pairingApi(), tv::deviceInfo, initial = started).states()
                    .filterIsInstance<PairingState.Approved>().first()
            }
            tv.signInWithPairing(approved.token, approved.user, serverId)
            val signedIn = tv.state.value as AuthState.SignedIn
            assertEquals(admin.user.id, signedIn.session.userId)
            assertEquals(admin.user.id, tv.requireClient().me().id)
            assertTrue(phone.devices().items.any { it.name == "E2E Setup TV" })

            // The QR is single use: a second POST of the same nonce finds the port closed.
            assertTrue(runCatching { TvSetupSender().send(scanned.link, baseUrl, serverId) }.isFailure)
            tv.requireClient().logout()
        } finally {
            listener.stop()
            phone.logout()
        }
    }
}
