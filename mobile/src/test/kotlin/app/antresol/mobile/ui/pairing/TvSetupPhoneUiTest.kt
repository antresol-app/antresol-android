package app.antresol.mobile.ui.pairing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.ApiException
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairDevice
import app.antresol.core.model.PairLookup
import app.antresol.core.model.Profile
import app.antresol.core.tvsetup.TvSetupAnswer
import app.antresol.core.tvsetup.TvSetupError
import app.antresol.core.tvsetup.TvSetupFailure
import app.antresol.core.tvsetup.TvSetupLink
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * «Set Up a TV» on the phone: the scanner recognises a TV's setup QR, the card shows this phone's
 * server and the profile, «Set Up» POSTs to the TV, then the returned code is looked up and
 * approved on the phone's own server. Screenshots: `$ANTRESOL_SHOTS_DIR` (default
 * `mobile/build/tv-setup-shots`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSetupPhoneUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File(System.getenv("ANTRESOL_SHOTS_DIR") ?: "build/tv-setup-shots").apply { mkdirs() }
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val serverUrl = "http://192.168.1.10:8420"
    private val qr = "http://192.168.1.50:41234/setup?n=AAAAAAAAAAAAAAAAAAAAAA&v=1"

    private class FakeApprover(var approveError: Exception? = null) : PairApprover {
        val calls = mutableListOf<String>()

        override suspend fun lookup(code: String): PairLookup {
            calls += "lookup $code"
            return PairLookup(code, PairDevice("Гостиная", "Android TV", "3.1.0"))
        }

        override suspend fun approve(code: String, userId: Long): PairApproved {
            approveError?.let {
                approveError = null
                throw it
            }
            calls += "approve $code $userId"
            return PairApproved(ok = true, userId = userId)
        }

        override suspend fun deny(code: String) {
            calls += "deny $code"
        }

        override suspend fun profiles(): List<Profile> = listOf(
            Profile(1, "Alex", "grad-1", hasPassword = true),
            Profile(2, "Kiddo", "grad-4"),
        )
    }

    private class FakeTv(val failures: ArrayDeque<Exception> = ArrayDeque(), val sid: String) {
        val posts = mutableListOf<String>()

        suspend fun send(link: TvSetupLink, server: String, serverId: String): TvSetupAnswer {
            failures.removeFirstOrNull()?.let { throw it }
            posts += "${link.host}:${link.port} ${link.nonce} $server $serverId"
            return TvSetupAnswer("ABC234", sid, server, "Гостиная")
        }
    }

    private fun scanner(text: String): @androidx.compose.runtime.Composable (onText: (String) -> Unit, modifier: Modifier) -> Unit =
        { onText, modifier ->
            Box(modifier.background(AntresolColors.FillSecondary), contentAlignment = Alignment.Center) {
                androidx.compose.material3.TextButton(onClick = { onText(text) }) { Text("camera") }
            }
        }

    private fun show(viewModel: PairTvViewModel, name: String = "Alex", avatar: String = "grad-1") = compose.setContent {
        AntresolTheme {
            PairTvScreen(viewModel, ownName = name, ownAvatar = avatar, onBack = {}, cameraAllowed = true, scanner = scanner(qr))
        }
    }

    @Test
    fun adminSetsUpATvForAnotherProfile() {
        val api = FakeApprover()
        val tv = FakeTv(sid = serverId)
        val viewModel = PairTvViewModel(api, serverId, ownUserId = 1, isAdmin = true, ownServerUrl = serverUrl, sendSetup = tv::send)
        show(viewModel)
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Set Up a TV"))
        compose.onNodeWithText("192.168.1.10:8420").assertExists()
        compose.onNodeWithText("The TV will connect to your server", substring = true).assertExists()
        // Nothing went to the TV yet: the card asks first.
        assertTrue(tv.posts.isEmpty())
        compose.onNodeWithTag("pair-profile-2").performScrollTo().performClick()
        shot("phone-setup-confirm")
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("Done. The TV is connecting and signing in."))
        shot("phone-setup-done")
        assertEquals(listOf("192.168.1.50:41234 AAAAAAAAAAAAAAAAAAAAAA $serverUrl $serverId"), tv.posts)
        assertEquals(listOf("lookup ABC234", "approve ABC234 2"), api.calls)
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-port-xxhdpi")
    fun confirmCardInRussian() {
        val viewModel = PairTvViewModel(FakeApprover(), serverId, ownUserId = 7, isAdmin = false, ownServerUrl = serverUrl, sendSetup = FakeTv(sid = serverId)::send)
        show(viewModel, "Kiddo", "grad-4")
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Настроить телевизор"))
        compose.onNodeWithText("Настроить").assertExists()
        shot("phone-setup-confirm-ru")
    }

    @Test
    fun nonAdminSendsItsOwnProfileAsZero() {
        val api = FakeApprover()
        val viewModel = PairTvViewModel(api, serverId, ownUserId = 7, isAdmin = false, ownServerUrl = serverUrl, sendSetup = FakeTv(sid = serverId)::send)
        show(viewModel, "Kiddo", "grad-4")
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Set Up a TV"))
        compose.onNodeWithTag("pair-profile-2").assertDoesNotExist()
        compose.onNodeWithTag("pair-profile-7").assertExists()
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("Done. The TV is connecting and signing in."))
        assertEquals(listOf("lookup ABC234", "approve ABC234 0"), api.calls)
    }

    @Test
    fun errorsFromTheTvAndARetryThatOnlyApproves() {
        val api = FakeApprover()
        val tv = FakeTv(
            ArrayDeque(listOf(TvSetupFailure(TvSetupError.ExpiredNonce, 404), IOException("connect timed out"), TvSetupFailure(TvSetupError.Unreachable, 502))),
            sid = serverId,
        )
        val viewModel = PairTvViewModel(api, serverId, ownUserId = 1, isAdmin = false, ownServerUrl = serverUrl, sendSetup = tv::send)
        show(viewModel)
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Set Up a TV"))

        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("The code has expired", substring = true))
        compose.onNodeWithText("Try Again").assertExists()
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("Can’t reach the TV", substring = true))
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("can’t reach your server", substring = true))
        shot("phone-setup-error")
        assertTrue(api.calls.isEmpty())

        // The TV takes the server, but the approval fails (e.g. a passwordless profile): the retry only approves.
        api.approveError = ApiException(403, "This profile has no password")
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("The TV is connected to the server, but the sign-in didn’t go through", substring = true))
        assertEquals(1, tv.posts.size)
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("Done. The TV is connecting and signing in."))
        assertEquals(1, tv.posts.size)
        assertEquals(listOf("lookup ABC234", "lookup ABC234", "approve ABC234 0"), api.calls)
    }

    @Test
    fun aTvThatJoinedAnotherServerIsNotApprovedAndCancelGoesBack() {
        val api = FakeApprover()
        val viewModel = PairTvViewModel(api, serverId, ownUserId = 1, isAdmin = false, ownServerUrl = serverUrl, sendSetup = FakeTv(sid = "f".repeat(32))::send)
        show(viewModel)
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Set Up a TV"))
        compose.onNodeWithTag("pair-setup").performScrollTo().performClick()
        waitFor(hasText("different Antresol server", substring = true))
        assertTrue(api.calls.isEmpty())
        compose.onNodeWithTag("pair-setup-cancel").performScrollTo().performClick()
        waitFor(hasText("Point the camera at the QR code on the TV."))
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
