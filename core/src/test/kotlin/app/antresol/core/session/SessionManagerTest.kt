package app.antresol.core.session

import app.antresol.core.api.ApiException
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    /** Lets a 401's `/api/me` check (real network on MockWebServer) finish after [requests] requests. */
    private suspend fun TestScope.settle(manager: SessionManager, requests: Int) {
        repeat(300) {
            advanceUntilIdle()
            if (server.requestCount >= requests && !manager.checkingSession()) return
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        fail("the session check did not finish")
    }

    private fun signedInAdmin(token: String = "tok"): StoredSession {
        val session = StoredSession(baseUrl, "srv1", 1, token, "Admin", "grad-1")
        kotlinx.coroutines.runBlocking { store.update { it.connected(baseUrl, "srv1").signedIn(session) } }
        return session
    }

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
        // the 401 is checked with /api/me once more: it says 401 too
        enqueue("""{"error":"Sign in required"}""", 401)
        manager.restore()
        settle(manager, 2)
        assertEquals(AuthState.ChooseProfile(baseUrl, "srv1", AuthNotice.SessionExpired), manager.state.value)
        assertNull(store.load().active)
        assertNull(store.load().tokenFor("srv1", 1))
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun refreshUserKeepsANewPhoto() = runTest {
        // A photo set on the web reaches the stored session on the next /api/me, so the top bar shows it
        // at once on the next launch too; removing it brings the letter back.
        store.update { it.connected(baseUrl, "srv1").signedIn(StoredSession(baseUrl, "srv1", 1, "tok", "Admin", "grad-1")) }
        val manager = manager()
        enqueue("""{"id":1,"name":"Admin","avatar":"grad-1","photo":"/api/profiles/1/photo?v=7"}""")
        manager.restore()
        assertEquals("/api/profiles/1/photo?v=7", store.load().active?.photo)
        assertEquals("/api/profiles/1/photo?v=7", (manager.state.value as AuthState.SignedIn).user?.photo)
        enqueue("""{"id":1,"name":"Admin","avatar":"grad-1"}""")
        manager.refreshUser()
        assertEquals("", store.load().active?.photo)
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

        // Picking the profile «Switch Profile» was opened from just goes back to it.
        manager.switchProfile()
        val asked = server.requestCount
        assertTrue(manager.resume(kid))
        assertEquals("tok-kid", (manager.state.value as AuthState.SignedIn).session.token)
        assertEquals(asked, server.requestCount)
    }

    @Test
    fun switchingBackToAPasswordlessProfileReusesItsSavedToken() = runTest {
        val manager = manager()
        store.update { it.connected(baseUrl, "srv1") }
        manager.restore()
        enqueue("""{"token":"tok-kid","user":${user(2, "Kiddo").replace("\"hasPassword\":true", "\"hasPassword\":false")}}""")
        assertTrue(manager.resume(kid))
        manager.switchProfile()
        enqueue("""{"token":"tok-admin","user":${user(1, "Admin")}}""")
        manager.signIn(admin, "secret")
        assertEquals(1L, (manager.state.value as AuthState.SignedIn).session.userId)
        // the kid token stays saved (no logout); switching to it again checks it with /api/me
        manager.switchProfile()
        enqueue(user(2, "Kiddo"))
        enqueue("", 204) // the admin token (a password profile) is revoked
        assertTrue(manager.resume(kid))
        server.takeRequest(); server.takeRequest()
        assertEquals("Bearer tok-kid", server.takeRequest().getHeader("Authorization"))
        assertEquals("tok-kid", (manager.state.value as AuthState.SignedIn).session.token)
        assertEquals("/api/logout", server.takeRequest().path)
        assertNull(store.load().tokenFor("srv1", 1))
    }

    @Test
    fun switchThenCancelKeepsTheSession() = runTest {
        val session = signedInAdmin()
        val manager = manager()
        enqueue(user(1, "Admin"))
        manager.restore()
        val signedIn = manager.state.value as AuthState.SignedIn
        manager.switchProfile()
        val chooser = manager.state.value as AuthState.ChooseProfile
        assertEquals(signedIn, chooser.switchingFrom)
        // nothing revoked or forgotten: a restart now is still signed in
        assertEquals(session, store.load().active)
        assertEquals("tok", manager.client?.token)
        assertTrue(manager.cancelSwitch())
        assertEquals(signedIn, manager.state.value)
        assertEquals(1, server.requestCount)
        assertFalse(manager.cancelSwitch())
    }

    @Test
    fun wrongPasswordWhileSwitchingKeepsTheSession() = runTest {
        val session = signedInAdmin()
        val manager = manager()
        enqueue(user(1, "Admin"))
        manager.restore()
        manager.switchProfile()
        enqueue("""{"error":"Wrong password"}""", 401)
        try {
            manager.signIn(Profile(3, "Other", "grad-2", hasPassword = true), "nope")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isUnauthorized)
        }
        assertEquals(session, store.load().active)
        assertTrue(manager.cancelSwitch())
        assertEquals("tok", (manager.state.value as AuthState.SignedIn).session.token)
    }

    @Test
    fun switchingToAnotherProfileRevokesAPasswordProfileToken() = runTest {
        signedInAdmin("tok-admin")
        val manager = manager()
        enqueue(user(1, "Admin"))
        manager.restore()
        manager.switchProfile()
        enqueue("""{"token":"tok-kid","user":${user(2, "Kiddo")}}""")
        enqueue("", 204)
        assertTrue(manager.resume(kid))
        assertEquals("tok-kid", (manager.state.value as AuthState.SignedIn).session.token)
        server.takeRequest() // restore's /api/me
        assertEquals("/api/auth/token", server.takeRequest().path)
        val logout = server.takeRequest()
        assertEquals("/api/logout", logout.path)
        assertEquals("Bearer tok-admin", logout.getHeader("Authorization"))
        assertNull(store.load().tokenFor("srv1", 1))
        assertEquals("tok-kid", store.load().active?.token)
    }

    @Test
    fun a401ElsewhereSignsOutOnlyWhenMeSays401() = runTest {
        val session = signedInAdmin()
        val manager = manager()
        enqueue(user(1, "Admin"))
        manager.restore()
        enqueue("""{"error":"nope"}""", 401)
        enqueue(user(1, "Admin"))
        try {
            manager.requireClient().devices()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isUnauthorized)
        }
        settle(manager, 3)
        assertTrue(manager.state.value is AuthState.SignedIn)
        assertEquals(session, store.load().active)
        // now /api/me answers 401 as well: the session has expired
        enqueue("""{"error":"nope"}""", 401)
        enqueue("""{"error":"Sign in required"}""", 401)
        try {
            manager.requireClient().devices()
        } catch (_: ApiException) {
        }
        settle(manager, 5)
        assertEquals(AuthState.ChooseProfile(baseUrl, "srv1", AuthNotice.SessionExpired), manager.state.value)
        assertNull(store.load().active)
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
