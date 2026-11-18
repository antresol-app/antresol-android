package app.antresol.ui.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Keeps an activity (or the application) in the profile's interface language after configuration changes it handles
 * itself (`configChanges`: a remote reconnecting, a keyboard, the screen size, the UI mode).
 *
 * The language is applied once, in `attachBaseContext`, as a configuration context. A change the
 * activity handles can rebase the activity's resources on the device configuration (it depends on
 * the Android version and on how the system reports the change), and it resets the process default
 * locale: strings read after it come back in the device language («All Channels» on a Russian
 * profile). The activity routes `getResources()` through [resources] and calls
 * [onConfigurationChanged]; both do nothing while the language holds or the profile has none.
 *
 * The application context needs it too: it is wrapped once per process, in the language cached
 * then, and a language that arrives later (the first sign-in, a profile switch) only restarts the
 * activity. View models, players and the media session that resolve text through the application
 * context would otherwise stay in the old language. The application sets [language] from its
 * settings on every `getResources()`. The same holds for a switch back to "follow the device": a
 * context that was wrapped in the old language is then served in the device's ([deviceLocales]).
 *
 * `getResources()` of the application is called from any thread (the media session service, IO
 * threads): [language] is volatile and the resources made are kept as one immutable value, so a
 * caller never gets resources made for another language.
 */
class ProfileLocaleKeeper(
    /** The device's own locales, which "follow the device" serves (the system resources are not wrapped). */
    private val deviceLocales: () -> LocaleList = { Resources.getSystem().configuration.locales },
) {
    /** "ru", "en", or "" to follow the device; set in `attachBaseContext` (a new language restarts the activity). */
    @Volatile
    var language: String = ""

    /** Resources made for [wanted] out of [basedOn] (the configuration they were made from). */
    private class Kept(val wanted: LocaleList, val basedOn: Configuration, val resources: Resources)

    @Volatile
    private var kept: Kept? = null

    /** [base] when it is in the wanted language (the profile's, or the device's when there is none), else a copy of it in it. */
    fun resources(context: Context, base: Resources): Resources {
        val wanted = language.let { if (it.isEmpty()) deviceLocales() else LocaleList(Locale.forLanguageTag(it)) }
        val config = base.configuration
        if (config.locales[0].language == wanted[0].language) return base
        kept?.let { if (it.wanted == wanted && it.basedOn == config) return it.resources }
        val fixed = Configuration(config).apply { setLocales(wanted) }
        return context.createConfigurationContext(fixed).resources.also {
            kept = Kept(wanted, Configuration(config), it)
        }
    }

    /** Puts the process default locale (formatters, `Accept-Language`) back to [language]. */
    fun onConfigurationChanged() {
        val language = language
        if (language.isEmpty()) return
        val locale = Locale.forLanguageTag(language)
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        if (LocaleList.getDefault()[0] != locale) LocaleList.setDefault(LocaleList(locale))
    }
}
