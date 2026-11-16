package app.antresol.tv.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.antresol.core.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.antresol.ui.language.ProfileLanguageCache
import java.util.Locale

/** User preferences of this TV (not per profile). */
data class TvPreferences(
    /** The profile's interface language (`effective` of `/api/me/ui-language`): "ru", "en", or "" for the system's. */
    val profileLanguage: String = "",
    /** A [Quality] value asked for when a video starts. */
    val defaultQuality: String = Quality.AUTO,
    /** ISO 639-1 code of the subtitle language to turn on automatically, or "" for off. */
    val subtitleLanguage: String = "",
)

/**
 * Stores [TvPreferences] in SharedPreferences. They are read synchronously, because the app
 * language must be known in `attachBaseContext` before any UI exists.
 */
class TvSettings(context: Context) : ProfileLanguageCache {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _preferences = MutableStateFlow(read())
    val preferences: StateFlow<TvPreferences> = _preferences.asStateFlow()

    val current: TvPreferences get() = _preferences.value

    override var profileLanguage: String
        get() = current.profileLanguage
        set(value) = update { it.copy(profileLanguage = value) }

    fun update(transform: (TvPreferences) -> TvPreferences) {
        val next = transform(current)
        val edit = prefs.edit()
            .putString(KEY_PROFILE_LANGUAGE, next.profileLanguage)
            .putString(KEY_QUALITY, next.defaultQuality)
            .putString(KEY_SUBTITLES, next.subtitleLanguage)
        // Only the language is written at once (the activity restarts and reads it right after);
        // the rest must not hold the main thread for a disk write.
        if (next.profileLanguage != current.profileLanguage) edit.commit() else edit.apply()
        _preferences.value = next
    }

    private fun read(): TvPreferences = TvPreferences(
        profileLanguage = prefs.getString(KEY_PROFILE_LANGUAGE, null).orEmpty(),
        defaultQuality = prefs.getString(KEY_QUALITY, null)?.takeIf { it in Quality.ALL } ?: Quality.AUTO,
        subtitleLanguage = prefs.getString(KEY_SUBTITLES, null).orEmpty(),
    )

    companion object {
        private const val FILE = "tv_settings"
        private const val KEY_PROFILE_LANGUAGE = "profile_ui_language"
        private const val KEY_QUALITY = "default_quality"
        private const val KEY_SUBTITLES = "subtitle_language"

        /** Subtitle languages offered in Settings (ISO 639-1). */
        val SUBTITLE_LANGUAGES = listOf("en", "ru", "uk", "de", "fr", "es", "it", "ja")

        private var systemLocales: LocaleList? = null

        /**
         * Applies the stored app language to [base] (use from `attachBaseContext`) and makes it the
         * process default, so formatters and the server's `Accept-Language` follow it.
         */
        fun wrap(base: Context): Context {
            if (systemLocales == null) systemLocales = LocaleList.getDefault()
            val language = TvSettings(base).current.profileLanguage
            val locales = language.ifEmpty { null }?.let { LocaleList(Locale.forLanguageTag(it)) } ?: systemLocales!!
            Locale.setDefault(locales[0])
            LocaleList.setDefault(locales)
            if (language.isEmpty()) return base
            val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
            return base.createConfigurationContext(config)
        }
    }
}
