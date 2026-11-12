package app.antresol.ui.language

import app.antresol.core.model.UiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiLanguageModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var server = UiLanguage(language = "", effective = "ru")
    private val puts = mutableListOf<String>()
    private var restarts = 0
    private val cache = object : ProfileLanguageCache {
        override var profileLanguage: String = ""
    }
    private val api = object : UiLanguageApi {
        override suspend fun get() = server

        override suspend fun set(language: String): UiLanguage {
            puts += language
            server = UiLanguage(language, if (language.isEmpty()) "ru" else language)
            return server
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theInterfaceFollowsTheProfile() = runTest(dispatcher) {
        val model = UiLanguageModel(api, cache) { restarts++ }
        model.refresh()
        runCurrent()
        assertEquals("ru", cache.profileLanguage)
        assertEquals("", model.choice)
        assertEquals(1, restarts)
        // Same language again: no restart.
        model.refresh()
        runCurrent()
        assertEquals(1, restarts)
        model.choose("en")
        runCurrent()
        assertEquals(listOf("en"), puts)
        assertEquals("en", cache.profileLanguage)
        assertEquals(2, restarts)
        // An unknown effective language follows the device.
        server = UiLanguage("", "de")
        model.refresh()
        runCurrent()
        assertEquals("", cache.profileLanguage)
    }
}
