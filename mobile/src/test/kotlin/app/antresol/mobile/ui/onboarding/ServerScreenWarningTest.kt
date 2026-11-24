package app.antresol.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.discovery.ServerFinder
import app.antresol.mobile.AntresolMobileApp
import app.antresol.ui.theme.AntresolTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The address screen says so when the typed address would send the password over the Internet in clear text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en")
class ServerScreenWarningTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()

    private fun typed(address: String) {
        compose.onNodeWithTag("server-address").performTextReplacement(address)
        compose.waitForIdle()
    }

    @Test
    fun theWarningShowsOnlyForAnAddressOutsideTheHomeNetworkWithoutHttps() {
        compose.setContent { AntresolTheme { ServerScreen(app.session, ServerFinder.None) } }
        compose.onNodeWithTag("server-clear-text-warning").assertDoesNotExist()
        typed("192.168.1.10:8420")
        compose.onNodeWithTag("server-clear-text-warning").assertDoesNotExist()
        typed("nas.local")
        compose.onNodeWithTag("server-clear-text-warning").assertDoesNotExist()
        typed("https://media.example.com")
        compose.onNodeWithTag("server-clear-text-warning").assertDoesNotExist()
        typed("media.example.com")
        compose.onNodeWithTag("server-clear-text-warning").assertIsDisplayed()
        typed("http://media.example.com:8420")
        compose.onNodeWithTag("server-clear-text-warning").assertIsDisplayed()
    }
}
