package app.antresol.core.session

import app.antresol.core.api.ApiException
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Profile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private val store = InMemorySessionStore(deviceId = "test-device-0001")

    private val admin = Profile(1, "Admin", "grad-1", hasPassword = true)
    private val kid = Profile(2, "Kiddo", "grad-4", hasPassword = false)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun TestScope.manager() = SessionManager(
        store = store,
        deviceInfo = { DeviceInfo(it, "Android TV (Test)", "Test", "0.1.0") },
        language = { "en" },
        scope = this,
    )

    private fun user(id: Long, name: String) =
        """{"id":$id,"name":"$name","avatar":"grad-1","hasPassword":true,"isAdmin":true}"""

    @Test
    fun emptyStoreNeedsServer() = runTest {
        val manager = manager()
        manager.restore()
        assertEquals(AuthState.NoServer, manager.state.value)
    }

    @Test
    fun connectValidatesHealthAndSetup() = runTest {
        val manager = manager()
        enqueue("""{"ok":true,"serverId":"srv1","version":"0.3.0"}""")
        enqueue("""{"needsSetup":false,"remote":false}""")
        manager.connect(baseUrl)
        assertEquals(AuthState.ChooseProfile(baseUrl, "srv1"), manager.state.value)
        assertEquals("/api/health", server.takeRequest().path)
        assertEquals("/api/setup", server.takeRequest().path)
        assertEquals("srv1", store.load().serverId)
    }

    @Test
    fun connectRejectsServerWithoutSetup() = runTest {
        val manager = manager()
        enqueue("""{"ok":true,"serverId":"srv1","version":"0.3.0"}""")
        enqueue("""{"needsSetup":true,"remote":false}""")
        try {
            manager.connect(baseUrl)
            fail("expected ServerNeedsSetupException")
        } catch (_: ServerNeedsSetupException) {
        }
        assertNull(store.load().serverUrl)
    }

    @Test
    fun signInPersistsTokenAndDeviceName() = runTest {
        val manager = manager()
        store.update { it.connected(baseUrl, "srv1") }
        manager.restore()
        enqueue("""{"token":"tok-admin","user":${user(1, "Admin")}}""")
        manager.signIn(admin, "admin123")
        val state = manager.state.value as AuthState.SignedIn
        assertEquals("tok-admin", state.session.token)
        assertEquals("tok-admin", store.load().active?.token)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"name\":\"Android TV (Test)\""))
        assertTrue(body.contains("\"id\":\"test-device-0001\""))
    }

    @Test
    fun restoreValidatesTokenAnd401ReturnsToProfiles() = runTest {
        store.update { it.connected(baseUrl, "srv1").signedIn(StoredSession(baseUrl, "srv1", 1, "old", "Admin", "grad-1")) }
        val manager = manager()
        enqueue("""{"error":"Sign in required"}""", 401)
        manager.restore()
        advanceUntilIdle()
        assertEquals(AuthState.ChooseProfile(baseUrl, "srv1", AuthNotice.SessionExpired), manager.state.value)
        assertNull(store.load().active)
        assertNull(store.load().tokenFor("srv1", 1))
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun restoreOfflineKeepsStoredSession() = runTest {
        val session = StoredSession(baseUrl, "srv1", 1, "tok", "Admin", "grad-1")
        store.update { it.connected(baseUrl, "srv1").signedIn(session) }
        server.shutdown()
        val manager = manager()
        manager.restore()
        assertEquals(AuthState.SignedIn(session, null), manager.state.value)
    }

    @Test
    fun resumeAsksForPasswordButSignsInPasswordlessProfiles() = runTest {
        val manager = manager()
        store.update { it.connected(baseUrl, "srv1") }
        manager.restore()
        assertFalse(manager.resume(admin))
        enqueue("""{"token":"tok-kid","user":${user(2, "Kiddo")}}""")
        assertTrue(manager.resume(kid))
        assertEquals("tok-kid", (manager.state.value as AuthState.SignedIn).session.token)

        // Switching back re-uses the saved kid token after checking it with /api/me.
        manager.switchProfile()
        enqueue(user(2, "Kiddo"))
        assertTrue(manager.resume(kid))
        assertEquals("tok-kid", server.run { takeRequest(); takeRequest() }.getHeader("Authorization")?.removePrefix("Bearer "))
    }

    @Test
    fun wrongPasswordStaysOnProfiles() = runTest {
        val manager = manager()
        store.update { it.connected(baseUrl, "srv1") }
        manager.restore()
        enqueue("""{"error":"Wrong password"}""", 401)
        try {
            manager.signIn(admin, "nope")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isUnauthorized)
        }
        assertTrue(manager.state.value is AuthState.ChooseProfile)
    }

    @Test
    fun signOutForgetsTokenEvenOffline() = runTest {
        val session = StoredSession(baseUrl, "srv1", 1, "tok", "Admin", "grad-1")
        store.update { it.connected(baseUrl, "srv1").signedIn(session) }
        val manager = manager()
        enqueue(user(1, "Admin"))
        manager.restore()
        enqueue("", 204)
        manager.signOut()
        assertEquals(AuthState.ChooseProfile(baseUrl, "srv1"), manager.state.value)
        assertNull(store.load().tokenFor("srv1", 1))
        manager.changeServer()
        assertEquals(AuthState.NoServer, manager.state.value)
        assertNull(store.load().serverUrl)
    }
}
