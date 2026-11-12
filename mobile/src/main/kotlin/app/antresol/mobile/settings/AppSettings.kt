package app.antresol.mobile.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.model.Quality
import app.antresol.ui.language.ProfileLanguageCache
import java.util.Locale

/**
 * Device-local preferences (not synced to the server). Backed by SharedPreferences because the
 * language must be known synchronously in `attachBaseContext`; values are Compose state so the
 * settings screen and the players see changes at once.
 */
class AppSettings(context: Context) : ProfileLanguageCache {
    private val prefs = (context.applicationContext ?: context).getSharedPreferences("antresol_settings", Context.MODE_PRIVATE)

    /**
     * The interface language the signed-in profile uses (`effective` of `/api/me/ui-language`):
     * "ru", "en", or "" to follow the phone. Kept here because `attachBaseContext` needs it at once.
     */
    override var profileLanguage: String
        get() = prefs.getString(KEY_PROFILE_LANGUAGE, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PROFILE_LANGUAGE, value).commit()
        }

    /** `/api/play` quality for new videos: one of [Quality.ALL]. */
    var videoQuality: String by mutableStateOf(prefs.getString(KEY_QUALITY, null)?.takeIf { it in Quality.ALL } ?: Quality.AUTO)
        private set

    /** Start the next episode after a 10 s countdown. */
    var autoplayNext: Boolean by mutableStateOf(prefs.getBoolean(KEY_AUTOPLAY, true))
        private set

    /** Audiobook playback speed, kept between books like the web app. */
    var audiobookSpeed: Float by mutableStateOf(prefs.getFloat(KEY_SPEED, 1f))
        private set

    /** The notification permission was asked once (Android 13+); never nag again. */
    var notificationsAsked: Boolean by mutableStateOf(prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false))
        private set

    fun updateVideoQuality(value: String) {
        videoQuality = value
        prefs.edit().putString(KEY_QUALITY, value).apply()
    }

    fun updateAutoplayNext(value: Boolean) {
        autoplayNext = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun updateAudiobookSpeed(value: Float) {
        audiobookSpeed = value
        prefs.edit().putFloat(KEY_SPEED, value).apply()
    }

    fun markNotificationsAsked() {
        notificationsAsked = true
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
    }

    /** The locale the UI uses: the profile's interface language, or the phone's. */
    fun effectiveLocale(): Locale = profileLanguage.ifEmpty { null }?.let { Locale.forLanguageTag(it) } ?: systemLocale()

    /** Returns [base] with the chosen language applied; activities call this from `attachBaseContext`. */
    fun wrap(base: Context): Context {
        val locale = effectiveLocale()
        Locale.setDefault(locale)
        if (profileLanguage.isEmpty()) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private companion object {
        const val KEY_PROFILE_LANGUAGE = "profile_ui_language"
        const val KEY_QUALITY = "video_quality"
        const val KEY_AUTOPLAY = "autoplay_next"
        const val KEY_SPEED = "audiobook_speed"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

        /** The phone's own locale (the JVM default may hold an override). */
        fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
    }
}
