package app.antresol.tv.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.ChannelPrefs
import app.antresol.core.model.DeviceHistoryRequest
import app.antresol.core.model.DeviceHistoryResult
import app.antresol.core.model.HistoryRef
import app.antresol.core.model.ManagedAccount
import app.antresol.core.model.Me
import app.antresol.core.model.UiLanguage
import app.antresol.core.session.AuthState
import app.antresol.core.session.StoredSession
import app.antresol.fixtures.Finding
import app.antresol.fixtures.ScreenAudit
import app.antresol.fixtures.auditReportOnly
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.settings.TvPreferences
import app.antresol.tv.settings.TvSettings
import app.antresol.ui.channels.ChannelPrefsApi
import app.antresol.ui.channels.ChannelPrefsModel
import app.antresol.ui.history.DeviceHistoryApi
import app.antresol.ui.history.DeviceHistoryModel
import app.antresol.ui.language.UiLanguageApi
import app.antresol.ui.language.UiLanguageModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every TV settings page and side panel in both languages at 1920x1080 and at a 1.3 font scale, with D-pad
 * focus where a person would have it: shots to `tv/build/screenshots/settings-audit/tv/<lang>-fs<scale>/`
 * and the findings of [ScreenAudit] (a text cut with «…», clipped, over another, outside the window, a key
 * shown as text, a missing translation). `SETTINGS_AUDIT_REPORT=1` only reports; otherwise a finding fails.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSettingsAuditTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var activity: android.app.Activity? = null

    private enum class Scene(
        val kid: Boolean = false,
        val historyOn: Boolean = false,
        val autostart: Boolean = false,
        val panel: Boolean = false,
    ) {
        Main,
        MainRow,
        MainAutostart(autostart = true),
        MainHistoryOn(historyOn = true),
        MainKid(kid = true),
        MainBottom,
        PanelQuality(panel = true),
        PanelSubtitles(panel = true),
        PanelLanguage(panel = true),
        PanelTarget(autostart = true, panel = true),
        PanelOverlayGuide(autostart = true, panel = true),
        PanelOverlayManual(autostart = true, panel = true),
        PanelHistory(panel = true),
        PanelHistoryNew(panel = true),
        PanelHistoryOff(historyOn = true, panel = true),
        PanelSignOut(panel = true),
    }

    private class Languages(private val effective: String) : UiLanguageApi {
        override suspend fun get() = UiLanguage("", effective)

        override suspend fun set(language: String) = UiLanguage(language, effective)
    }

    private class Prefs : ChannelPrefsApi {
        override suspend fun get() = ChannelPrefs(favoritesFirst = true)

        override suspend fun set(prefs: ChannelPrefs) = prefs
    }

    private class Histories(private val names: List<String>) : DeviceHistoryApi {
        override suspend fun accounts() = names.mapIndexed { index, name -> ManagedAccount(20L + index, name, "grad-2") }

        override suspend fun create(name: String) = ManagedAccount(99, name)

        override suspend fun switch(request: DeviceHistoryRequest) = DeviceHistoryResult()
    }

    @Composable
    private fun Content(scene: Scene, language: String, settings: TvSettings) {
        val user = remember(scene) {
            Me(
                id = 1, name = "Alex", avatar = "grad-3", isAdmin = !scene.kid, isKid = scene.kid,
                deviceHistory = if (scene.historyOn) HistoryRef(20, "Living Room") else null,
            )
        }
        val state = remember(user) { AuthState.SignedIn(StoredSession("http://server.test", "srv", 1, "t", "Alex", "grad-3"), user) }
        val languages = remember(scene) { UiLanguageModel(Languages(language), settings) {} }
        val prefs = remember(scene) { ChannelPrefsModel(Prefs()) }
        val histories = remember(scene) {
            DeviceHistoryModel(Histories(listOf("Living Room", "Guest Room With a Rather Long Name")), onSwitched = {})
        }
        AntresolTvTheme {
            key(scene) {
                SettingsScreen(
                    state = state,
                    settings = settings,
                    onSwitchProfile = {},
                    onSignOut = {},
                    onScrolledToTop = {},
                    onMenuOpenChanged = {},
                    language = languages,
                    deviceHistory = if (scene.kid) null else histories,
                    channelPrefs = prefs,
                )
            }
        }
    }

    private lateinit var audit: ScreenAudit

    private fun settle() = audit.settle(6)

    private fun press(code: Int) {
        compose.runOnUiThread {
            activity!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            activity!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        settle()
    }

    /** The list composes only the rows near the focus: walk down with the D-pad, as a person does, until [label] is there. */
    private fun reveal(label: String) {
        val row = hasClickAction() and hasText(label)
        if (compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()) return
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(16) {
            if (compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()) return
            press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    /** The row or pill showing [label] (in the side panel when [inPanel]), focused and then pressed with OK. */
    private fun open(label: String, inPanel: Boolean = false) {
        if (!inPanel) reveal(label)
        val shown = hasClickAction() and hasText(label)
        val node = compose.onAllNodes(if (inPanel) shown and hasAnyAncestor(hasTestTag("options-panel")) else shown).onFirst()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        node.performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun focusRow(label: String) {
        reveal(label)
        compose.onAllNodes(hasClickAction() and hasText(label)).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    /** Robolectric does not move focus into a side panel: the way a TV does it, the panel's first (or checked) row. */
    private fun focusPanel(index: Int = 0) {
        val rows = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("options-panel")))
        rows[index].performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    private fun focusPanelRow(label: String) {
        compose.onAllNodes(hasClickAction() and hasText(label) and hasAnyAncestor(hasTestTag("options-panel")))
            .onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    private fun drive(scene: Scene, strings: Strings) {
        when (scene) {
            Scene.Main -> Unit
            Scene.MainRow -> focusRow(strings.quality)
            Scene.MainAutostart -> focusRow(strings.permission)
            Scene.MainHistoryOn -> focusRow(strings.history)
            Scene.MainKid -> Unit
            Scene.MainBottom -> focusRow(strings.languageRow)
            Scene.PanelQuality -> { open(strings.quality); focusPanel() }
            Scene.PanelSubtitles -> { open(strings.subtitles); focusPanel() }
            Scene.PanelLanguage -> { open(strings.languageRow); focusPanel() }
            Scene.PanelTarget -> { open(strings.target); focusPanel() }
            Scene.PanelOverlayGuide -> { open(strings.permission); focusPanel() }
            Scene.PanelOverlayManual -> { open(strings.permission); open(strings.openSettings, inPanel = true); focusPanel() }
            Scene.PanelHistory -> { open(strings.history); focusPanel() }
            Scene.PanelHistoryNew -> { open(strings.history); open(strings.newHistory, inPanel = true) }
            Scene.PanelHistoryOff -> { open(strings.history); open(strings.off, inPanel = true); focusPanel() }
            Scene.PanelSignOut -> { open(strings.signOut); focusPanel() }
        }
    }

    /** The texts the scenes press, in the language under test. */
    private class Strings(language: String) {
        private val ru = language == "ru"
        val quality = if (ru) "Качество видео" else "Video Quality"
        val subtitles = if (ru) "Субтитры" else "Subtitles"
        val languageRow = if (ru) "Язык интерфейса" else "Interface Language"
        val target = if (ru) "Что открывать" else "What to open"
        val permission = if (ru) "Поверх других приложений" else "Display over other apps"
        val openSettings = if (ru) "Открыть настройки" else "Open settings"
        val history = if (ru) "Отдельная история на этом устройстве" else "Separate History on This Device"
        val newHistory = if (ru) "Новая история…" else "New History…"
        val off = if (ru) "Выкл." else "Off"
        val signOut = if (ru) "Выйти" else "Sign Out"
    }

    @Test
    fun everySettingsSceneInEnglish() = everyScene("en")

    @Test
    @Config(qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
    fun everySettingsSceneInRussian() = everyScene("ru")

    private fun everyScene(language: String) {
        val out = File("build/screenshots/settings-audit/tv")
        val settings = TvSettings(app)
        val problems = ArrayList<String>()
        // The names of the two languages are written in themselves, whatever the interface speaks.
        val data = { text: String -> text in setOf("Alex", "Living Room", "Guest Room With a Rather Long Name", "Guest Room With a Rather Long…", "Русский", "English") }
        for (scale in listOf(1.0f, 1.3f)) {
            audit = ScreenAudit(compose, File(out, "$language-fs${(scale * 10).toInt()}"))
            val strings = Strings(language)
            // One window per scene: a page left and entered again was seen to keep the test waiting.
            for (scene in Scene.entries) {
                settings.update {
                    TvPreferences(profileLanguage = language, autostart = scene.autostart, autostartTarget = it.autostartTarget)
                }
                val scenario = audit.launch(language, scale, manualClock = true) { Content(scene, language, settings) }
                // No settings page of the system exists here: opening it fails, as on a TV without one.
                shadowOf(app).checkActivities(true)
                activity = scenario.get()
                settle()
                try {
                    drive(scene, strings)
                } catch (e: AssertionError) {
                    audit.findings += Finding(scene.name, "driver-failed", "", e.message.orEmpty().lines().first())
                }
                audit.shot(scene.name, activity!!)
                audit.report(scene.name, language, only = if (scene.panel) "options-panel" else null, allowed = data, mayCut = { it.startsWith("Guest Room With") }, focusExpected = true)
                scenario.pause().stop().destroy()
            }
            problems += audit.write().lines().filter { it.isNotBlank() }.map { "$language fs$scale: $it" }
        }
        File(out, "findings-$language.tsv").writeText(problems.joinToString("\n") + "\n")
        if (!auditReportOnly()) assertTrue("Findings:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
