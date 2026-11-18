package app.antresol.tv.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.R
import app.antresol.ui.language.ProfileLocaleKeeper
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The profile's language holds over resources that came back in the device's language (a
 * configuration change the activity handled), and nothing changes while it holds or there is none.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class ProfileLocaleKeeperTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saved = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(saved)
        LocaleList.setDefault(LocaleList(saved))
    }

    @Test
    fun deviceLanguageResourcesAreServedInTheProfileLanguage() {
        val keeper = ProfileLocaleKeeper().apply { language = "ru" }
        val english = context.resources
        assertEquals("All Channels", english.getString(R.string.tv_all_channels))
        val kept = keeper.resources(context, english)
        assertEquals("Все каналы", kept.getString(R.string.tv_all_channels))
        assertEquals("до 21:40", kept.getString(R.string.tv_until, "21:40"))
        assertSame("made once per configuration", kept, keeper.resources(context, english))
    }

    @Test
    fun resourcesAlreadyInTheLanguageOrWithoutOneAreLeftAlone() {
        val english = context.resources
        assertSame(english, ProfileLocaleKeeper().resources(context, english))
        assertSame(english, ProfileLocaleKeeper().apply { language = "en" }.resources(context, english))
        val russian = context.createConfigurationContext(
            Configuration(english.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag("ru"))) },
        ).resources
        assertSame(russian, ProfileLocaleKeeper().apply { language = "ru" }.resources(context, russian))
    }

    @Test
    fun theDefaultLocaleIsPutBack() {
        Locale.setDefault(Locale.ENGLISH)
        LocaleList.setDefault(LocaleList(Locale.ENGLISH))
        ProfileLocaleKeeper().apply { language = "ru" }.onConfigurationChanged()
        assertEquals("ru", Locale.getDefault().language)
        assertEquals("ru", LocaleList.getDefault()[0].language)
        ProfileLocaleKeeper().onConfigurationChanged()
        assertEquals("a keeper without a language leaves it", "ru", Locale.getDefault().language)
    }
}
