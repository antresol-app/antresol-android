package app.antresol.ui.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity

/**
 * An activity in the profile's interface language, also after configuration changes it handles
 * itself (see [ProfileLocaleKeeper]). Every activity of the apps extends it, so none can miss the
 * three overrides and show a screen in the device's language. The app says where the profile's
 * language is kept ([profileLanguage]) and how a context is put into it ([wrap]).
 */
abstract class LocalizedActivity : ComponentActivity() {
    private val locale = ProfileLocaleKeeper()

    /** The profile's language ("ru", "en", or "" to follow the device) as the app's settings hold it. */
    protected abstract fun profileLanguage(base: Context): String

    /** [base] with the profile's language applied (the app's settings also make it the process default). */
    protected abstract fun wrap(base: Context): Context

    override fun attachBaseContext(newBase: Context) {
        locale.language = profileLanguage(newBase)
        super.attachBaseContext(wrap(newBase))
    }

    override fun getResources(): Resources = locale.resources(this, super.getResources())

    override fun onConfigurationChanged(newConfig: Configuration) {
        locale.onConfigurationChanged()
        super.onConfigurationChanged(newConfig)
    }
}
