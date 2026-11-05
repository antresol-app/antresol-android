package app.antresol.tv.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.antresol.core.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** The app language: follow the system, or force English or Russian. */
enum class AppLanguage(val tag: String?) { System(null), English("en"), Russian("ru") }

/** User preferences of this TV (not per profile). */
data class TvPreferences(
    val language: AppLanguage = AppLanguage.System,
    /** A [Quality] value asked for when a video starts. */
    val defaultQuality: String = Quality.AUTO,
    /** ISO 639-1 code of the subtitle language to turn on automatically, or "" for off. */
    val subtitleLanguage: String = "",
)

/**
 * Stores [TvPreferences] in SharedPreferences. They are read synchronously, because the app
 * language must be known in `attachBaseContext` before any UI exists.
 */
class TvSettings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _preferences = MutableStateFlow(read())
    val preferences: StateFlow<TvPreferences> = _preferences.asStateFlow()

    val current: TvPreferences get() = _preferences.value

    fun update(transform: (TvPreferences) -> TvPreferences) {
        val next = transform(current)
        prefs.edit()
            .putString(KEY_LANGUAGE, next.language.name)
            .putString(KEY_QUALITY, next.defaultQuality)
            .putString(KEY_SUBTITLES, next.subtitleLanguage)
            .apply()
        _preferences.value = next
    }

    private fun read(): TvPreferences = TvPreferences(
        language = prefs.getString(KEY_LANGUAGE, null)?.let { name -> AppLanguage.entries.firstOrNull { it.name == name } } ?: AppLanguage.System,
        defaultQuality = prefs.getString(KEY_QUALITY, null)?.takeIf { it in Quality.ALL } ?: Quality.AUTO,
        subtitleLanguage = prefs.getString(KEY_SUBTITLES, null).orEmpty(),
    )

    companion object {
        private const val FILE = "tv_settings"
        private const val KEY_LANGUAGE = "language"
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
            val language = TvSettings(base).current.language
            val locales = language.tag?.let { LocaleList(Locale.forLanguageTag(it)) } ?: systemLocales!!
            Locale.setDefault(locales[0])
            LocaleList.setDefault(locales)
            if (language == AppLanguage.System) return base
            val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
            return base.createConfigurationContext(config)
        }
    }
}
