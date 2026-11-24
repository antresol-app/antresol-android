package app.antresol.mobile

import android.content.Context
import app.antresol.mobile.settings.AppSettings
import app.antresol.ui.language.LocalizedActivity

/** The base of the phone app's activities: the profile's language from [AppSettings]. */
abstract class MobileActivity : LocalizedActivity() {
    override fun profileLanguage(base: Context): String = AppSettings(base).profileLanguage

    override fun wrap(base: Context): Context = AppSettings(base).wrap(base)
}
