package app.antresol.ui.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.antresol.core.api.AntresolClient
import app.antresol.core.session.SessionState
import app.antresol.core.session.StoredSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreSessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("session.preferences_pb").also { it.delete() } }
    }

    /** Reversible and recognisable; [broken] makes every call fail like a dead Keystore. */
    private class FakeCipher(var broken: Boolean = false) : TokenCipher {
        override fun encrypt(plain: String): String {
            if (broken) throw IllegalStateException("Keystore unavailable")
            return plain.reversed()
        }

        override fun decrypt(sealed: String): String {
            if (broken) throw IllegalStateException("Keystore unavailable")
            return sealed.reversed()
        }
    }

    private val session = StoredSession(serverUrl = "http://nas:8420", serverId = "srv", userId = 1, token = "secret-token", userName = "Admin")
    private val signedIn = SessionState().signedIn(session)

    @After
    fun tearDown() = scope.cancel()

    private fun raw(): String? = runBlocking { dataStore.data.first()[STATE] }

    @Test
    fun tokensAreSealedAtRest() = runBlocking {
        val store = DataStoreSessionStore(dataStore, FakeCipher())
        store.update { signedIn }
        val stored = raw()!!
        assertTrue(stored.startsWith("sealed1:"))
        assertFalse(stored.contains("secret-token"))
        assertEquals(signedIn, store.load())
    }

    @Test
    fun plainStateFromAnOlderVersionIsMigrated() = runBlocking {
        dataStore.edit { it[STATE] = AntresolClient.json.encodeToString(SessionState.serializer(), signedIn) }
        val store = DataStoreSessionStore(dataStore, FakeCipher())
        assertEquals(signedIn, store.load())
        val stored = raw()!!
        assertTrue(stored.startsWith("sealed1:"))
        assertFalse(stored.contains("secret-token"))
    }

    @Test
    fun brokenKeystoreFallsBackToPlainStorage() = runBlocking {
        val cipher = FakeCipher(broken = true)
        val store = DataStoreSessionStore(dataStore, cipher)
        store.update { signedIn }
        assertEquals(signedIn, store.load())
        assertTrue(raw()!!.startsWith("{"))
        // Once the Keystore works again, the next load seals the state.
        cipher.broken = false
        assertEquals(signedIn, store.load())
        assertTrue(raw()!!.startsWith("sealed1:"))
    }

    @Test
    fun unreadableSealedStateReadsAsSignedOut() = runBlocking {
        val cipher = FakeCipher()
        val store = DataStoreSessionStore(dataStore, cipher)
        store.update { signedIn }
        cipher.broken = true
        assertEquals(SessionState(), store.load())
        // The app can still sign in and save (unencrypted until the Keystore recovers).
        assertEquals(signedIn, store.update { signedIn })
    }

    @Test
    fun deviceIdIsStable() = runBlocking {
        val store = DataStoreSessionStore(dataStore, FakeCipher())
        assertEquals(store.deviceId(), store.deviceId())
    }

    private companion object {
        val STATE = stringPreferencesKey("session_state")
    }
}
