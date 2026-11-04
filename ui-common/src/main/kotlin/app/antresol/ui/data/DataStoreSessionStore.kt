package app.antresol.ui.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.antresol.core.api.AntresolClient
import app.antresol.core.session.DeviceIds
import app.antresol.core.session.SessionState
import app.antresol.core.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.antresolSession: DataStore<Preferences> by preferencesDataStore(name = "antresol_session")

/**
 * [SessionStore] backed by Jetpack DataStore in the app's private storage. Tokens are opaque
 * bearer strings; app-private storage is the same protection the web app's cookie gets.
 */
class DataStoreSessionStore(context: Context) : SessionStore {
    private val store = context.applicationContext.antresolSession

    /** Emits the state whenever it changes. */
    val state: Flow<SessionState> = store.data.map { decode(it[STATE]) }

    override suspend fun deviceId(): String {
        val existing = store.data.first()[DEVICE_ID]
        if (existing != null && DeviceIds.isValid(existing)) return existing
        var id = ""
        store.edit { prefs ->
            id = DeviceIds.validOrNew(prefs[DEVICE_ID])
            prefs[DEVICE_ID] = id
        }
        return id
    }

    override suspend fun load(): SessionState = state.first()

    override suspend fun update(transform: (SessionState) -> SessionState): SessionState {
        var result = SessionState()
        store.edit { prefs ->
            result = transform(decode(prefs[STATE]))
            prefs[STATE] = AntresolClient.json.encodeToString(SessionState.serializer(), result)
        }
        return result
    }

    private fun decode(raw: String?): SessionState =
        raw?.let { runCatching { AntresolClient.json.decodeFromString(SessionState.serializer(), it) }.getOrNull() }
            ?: SessionState()

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val STATE = stringPreferencesKey("session_state")
    }
}
