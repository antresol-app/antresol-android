package app.antresol.tv.ui.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.ApiException
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.ui.R
import app.antresol.ui.onboarding.connectMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException

/** What the server screens say when connecting fails. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class ConnectMessageTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    /** An [ApiException] is an [IOException]: the server's own answer must not read as a network failure. */
    @Test
    fun anHttpErrorIsTheServersSentenceNotANetworkFailure() {
        assertEquals("The database is locked", ApiException(503, "The database is locked").connectMessage(resources))
    }

    @Test
    fun aNetworkFailureSaysTheServerCannotBeReached() {
        val unreachable = resources.getString(R.string.server_unreachable)
        assertEquals(unreachable, ConnectException("refused").connectMessage(resources))
        assertEquals(unreachable, IOException("timeout").connectMessage(resources))
    }

    @Test
    fun theOtherRefusalsKeepTheirOwnSentences() {
        assertEquals(resources.getString(R.string.server_not_antresol), NotAntresolServerException().connectMessage(resources))
        assertEquals(resources.getString(R.string.server_needs_setup), ServerNeedsSetupException().connectMessage(resources))
        assertEquals(resources.getString(R.string.server_invalid_address), InvalidServerAddressException("empty").connectMessage(resources))
    }
}
