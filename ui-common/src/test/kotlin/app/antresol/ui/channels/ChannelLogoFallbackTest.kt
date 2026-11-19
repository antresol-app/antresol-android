package app.antresol.ui.channels

import app.antresol.core.model.ChannelPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelLogoFallbackTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theLogoThenTheGuideIconAreTried() {
        assertEquals(listOf("http://pl.test/a.png", "http://epg.test/1.png"), logoSources("http://pl.test/a.png", "http://epg.test/1.png"))
        // No playlist logo: the guide icon alone; neither: nothing (the row shows the initials).
        assertEquals(listOf("http://epg.test/1.png"), logoSources(null, "http://epg.test/1.png"))
        assertEquals(listOf("http://epg.test/1.png"), logoSources("", "http://epg.test/1.png"))
        assertEquals(listOf("/api/channels/2/logo.png?v=1"), logoSources("/api/channels/2/logo.png?v=1", "/api/channels/2/logo.png?v=1"))
        assertEquals(emptyList<String>(), logoSources(null, null))
    }

    @Test
    fun aChannelsTileColourIsTheSameOnEveryPlatform() {
        // The web's channelTone: trimmed, lower-cased code points, h = (h * 31 + cp) >>> 0, grad-(h % 7 + 1).
        assertEquals("grad-6", channelTileGradient("Documentaries"))
        assertEquals("grad-4", channelTileGradient("Первый канал"))
        assertEquals("grad-7", channelTileGradient("  All Series "))
        assertEquals("grad-2", channelTileGradient("Кино"))
        assertEquals(channelTileGradient("кино"), channelTileGradient("КИНО"))
    }

    @Test
    fun initialsAsTheWebMakesThem() {
        assertEquals("ПК", channelInitials("Первый канал"))
        assertEquals("Д", channelInitials("Дождь HD"))
        assertEquals("RН", channelInitials("RU | Новости 1080p"))
        assertEquals("CH", channelInitials("Comedy-Hits +2"))
        assertEquals("2", channelInitials("2x2"))
        assertEquals("?", channelInitials("  "))
    }

    @Test
    fun favouritesFirstIsTheProfilesOnTheServerAndGoesBackWhenRefused() = runTest(dispatcher) {
        var stored = ChannelPrefs(favoritesFirst = true)
        var refuse = false
        var changed = 0
        val model = ChannelPrefsModel(
            object : ChannelPrefsApi {
                override suspend fun get() = stored

                override suspend fun set(prefs: ChannelPrefs): ChannelPrefs {
                    if (refuse) throw IOException("offline")
                    stored = prefs
                    return prefs
                }
            },
            onChanged = { changed++ },
        )
        assertNull(model.favoritesFirst)
        model.refresh()
        runCurrent()
        assertEquals(true, model.favoritesFirst)
        model.setFavoritesFirst(false)
        assertEquals(false, model.favoritesFirst)
        runCurrent()
        assertEquals(ChannelPrefs(favoritesFirst = false), stored)
        assertEquals(1, changed)
        refuse = true
        model.setFavoritesFirst(true)
        runCurrent()
        assertEquals(false, model.favoritesFirst)
        assertEquals(1, changed)
    }

    @Test
    fun anOlderServerWithoutPrefsHidesTheSetting() = runTest(dispatcher) {
        val model = ChannelPrefsModel(
            object : ChannelPrefsApi {
                override suspend fun get(): ChannelPrefs = throw IOException("404")

                override suspend fun set(prefs: ChannelPrefs): ChannelPrefs = throw IOException("404")
            },
        )
        model.refresh()
        runCurrent()
        assertNull(model.favoritesFirst)
        model.setFavoritesFirst(false)
        runCurrent()
        assertNull(model.favoritesFirst)
    }
}
