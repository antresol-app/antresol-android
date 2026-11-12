package app.antresol.ui.language

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.UiLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The interface languages the apps have; "" is «как у профиля» (the profile's own language). */
object UiLanguages {
    const val PROFILE = ""
    const val RUSSIAN = "ru"
    const val ENGLISH = "en"
    val CHOICES = listOf(PROFILE, RUSSIAN, ENGLISH)

    /** The server's `effective`, kept to what the apps can show ("" = follow the device). */
    fun normalize(effective: String): String = effective.lowercase().takeIf { it == RUSSIAN || it == ENGLISH }.orEmpty()
}

/**
 * Where an app keeps the profile's effective interface language between launches: it must be known
 * synchronously in `attachBaseContext`, before the server can be asked.
 */
interface ProfileLanguageCache {
    /** "ru", "en", or "" to follow the device. */
    var profileLanguage: String
}

/** The server calls of the interface language; [AntresolClient.uiLanguageApi] is the real one. */
interface UiLanguageApi {
    suspend fun get(): UiLanguage

    suspend fun set(language: String): UiLanguage
}

fun AntresolClient.uiLanguageApi(): UiLanguageApi = object : UiLanguageApi {
    override suspend fun get() = uiLanguage()

    override suspend fun set(language: String) = setUiLanguage(language)
}

/**
 * The app's interface follows the profile (`GET /api/me/ui-language` → `effective`): [refresh] asks
 * the server and, when the language differs from the cached one, stores it and calls [onChanged]
 * (the app restarts its screens in it). [choose] writes the profile's choice («Язык интерфейса»).
 * The cache also feeds `Accept-Language`, so the server's names come back in the same language.
 */
class UiLanguageModel(
    private val api: UiLanguageApi,
    private val cache: ProfileLanguageCache,
    private val onChanged: () -> Unit,
) : ViewModel() {
    /** The profile's own choice ("", "ru", "en"), or null until it was read. */
    var choice: String? by mutableStateOf(null)
        private set

    var saving: Boolean by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch { apply(runCatchingNonCancel { api.get() } ?: return@launch) }
    }

    fun choose(language: String) {
        if (language == choice || saving) return
        saving = true
        viewModelScope.launch {
            try {
                apply(runCatchingNonCancel { api.set(language) } ?: return@launch)
            } finally {
                saving = false
            }
        }
    }

    private fun apply(state: UiLanguage) {
        choice = state.language
        val effective = UiLanguages.normalize(state.effective)
        if (effective != cache.profileLanguage) {
            cache.profileLanguage = effective
            onChanged()
        }
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
