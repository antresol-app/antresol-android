package app.antresol.ui.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.antresol.core.api.AntresolClient
import app.antresol.core.session.DeviceIds
import app.antresol.core.session.SessionState
import app.antresol.core.session.SessionStore
import kotlinx.coroutines.flow.first

private val Context.antresolSession: DataStore<Preferences> by preferencesDataStore(name = "antresol_session")

/**
 * [SessionStore] backed by Jetpack DataStore in the app's private storage (excluded from backup and
 * device transfer). The state, which holds the device tokens, is sealed with [cipher] (an
 * AndroidKeyStore key by default).
 *
 * - Migration: a state written before encryption (plain JSON) is read as is and sealed on first load.
 * - Fallback: when the cipher fails to seal, the state is stored as plain JSON (as before) and the
 *   failure is logged, so sign-in keeps working on a device with a broken Keystore. A sealed state
 *   that cannot be opened any more (its key is gone) reads as signed out, so the user signs in again.
 */
class DataStoreSessionStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : SessionStore {
    constructor(context: Context) : this(context.applicationContext.antresolSession, KeystoreTokenCipher())

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

    override suspend fun load(): SessionState {
        val raw = store.data.first()[STATE]
        // Written before encryption (or while the cipher failed): seal it now.
        if (raw != null && !raw.startsWith(SEALED_PREFIX)) return update { it }
        return decode(raw)
    }

    override suspend fun update(transform: (SessionState) -> SessionState): SessionState {
        var result = SessionState()
        store.edit { prefs ->
            result = transform(decode(prefs[STATE]))
            prefs[STATE] = encode(result)
        }
        return result
    }

    private fun encode(state: SessionState): String {
        val json = AntresolClient.json.encodeToString(SessionState.serializer(), state)
        return try {
            SEALED_PREFIX + cipher.encrypt(json)
        } catch (e: Exception) {
            Log.w(TAG, "Could not encrypt the session; storing it unencrypted", e)
            json
        }
    }

    private fun decode(raw: String?): SessionState {
        if (raw == null) return SessionState()
        val json = if (raw.startsWith(SEALED_PREFIX)) {
            try {
                cipher.decrypt(raw.removePrefix(SEALED_PREFIX))
            } catch (e: Exception) {
                Log.w(TAG, "Could not decrypt the stored session; signing in again", e)
                return SessionState()
            }
        } else {
            raw
        }
        return runCatching { AntresolClient.json.decodeFromString(SessionState.serializer(), json) }.getOrNull() ?: SessionState()
    }

    private companion object {
        const val TAG = "AntresolSession"

        /** Marks a sealed state; plain states are JSON objects and start with `{`. */
        const val SEALED_PREFIX = "sealed1:"
        val DEVICE_ID = stringPreferencesKey("device_id")
        val STATE = stringPreferencesKey("session_state")
    }
}
