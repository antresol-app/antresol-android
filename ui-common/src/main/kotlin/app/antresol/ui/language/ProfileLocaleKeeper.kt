package app.antresol.ui.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Keeps an activity in the profile's interface language after configuration changes it handles
 * itself (`configChanges`: a remote reconnecting, a keyboard, the screen size, the UI mode).
 *
 * The language is applied once, in `attachBaseContext`, as a configuration context. A change the
 * activity handles can rebase the activity's resources on the device configuration (it depends on
 * the Android version and on how the system reports the change), and it resets the process default
 * locale: strings read after it come back in the device language («All Channels» on a Russian
 * profile). The activity routes `getResources()` through [resources] and calls
 * [onConfigurationChanged]; both do nothing while the language holds or the profile has none.
 */
class ProfileLocaleKeeper {
    /** "ru", "en", or "" to follow the device; set in `attachBaseContext` (a new language restarts the activity). */
    var language: String = ""

    private var basedOn: Configuration? = null
    private var kept: Resources? = null

    /** [base] when it is in [language] (or there is none), else a copy of it in [language]. */
    fun resources(context: Context, base: Resources): Resources {
        val wanted = language.ifEmpty { return base }
        val config = base.configuration
        if (config.locales[0].language == wanted) return base
        kept?.let { if (basedOn == config) return it }
        val fixed = Configuration(config).apply { setLocales(LocaleList(Locale.forLanguageTag(wanted))) }
        return context.createConfigurationContext(fixed).resources.also {
            kept = it
            basedOn = Configuration(config)
        }
    }

    /** Puts the process default locale (formatters, `Accept-Language`) back to [language]. */
    fun onConfigurationChanged() {
        if (language.isEmpty()) return
        val locale = Locale.forLanguageTag(language)
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        if (LocaleList.getDefault()[0] != locale) LocaleList.setDefault(LocaleList(locale))
    }
}
