package app.antresol.ui.channels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.attempt
import app.antresol.core.model.ChannelPrefs
import kotlinx.coroutines.launch

/** The server calls of the channel list's choices; [AntresolClient.channelPrefsApi] is the real one. */
interface ChannelPrefsApi {
    suspend fun get(): ChannelPrefs

    suspend fun set(prefs: ChannelPrefs): ChannelPrefs
}

fun AntresolClient.channelPrefsApi(): ChannelPrefsApi = object : ChannelPrefsApi {
    override suspend fun get() = channelPrefs()

    override suspend fun set(prefs: ChannelPrefs) = setChannelPrefs(prefs)
}

/**
 * «Избранные каналы первыми» in the settings: the profile's choice on the server (`GET|PUT
 * /api/channels/prefs`, every device of the profile; the server orders `GET /api/channels` by it).
 * [favoritesFirst] is null until read and on an older server without it: the setting is then not shown.
 * [setFavoritesFirst] shows the new value at once and goes back when the server refuses it; once
 * stored, [onChanged] runs (the app reloads its channel list, which the server now orders anew).
 */
class ChannelPrefsModel(private val api: ChannelPrefsApi, private val onChanged: () -> Unit = {}) : ViewModel() {
    var favoritesFirst: Boolean? by mutableStateOf(null)
        private set

    private var saving = false

    fun refresh() {
        if (saving) return
        viewModelScope.launch { attempt { api.get() }?.let { if (!saving) favoritesFirst = it.favoritesFirst } }
    }

    fun setFavoritesFirst(on: Boolean) {
        val before = favoritesFirst ?: return
        if (on == before || saving) return
        saving = true
        favoritesFirst = on
        viewModelScope.launch {
            try {
                val stored = attempt { api.set(ChannelPrefs(favoritesFirst = on)) }
                favoritesFirst = stored?.favoritesFirst ?: before
                if (stored != null) onChanged()
            } finally {
                saving = false
            }
        }
    }
}
