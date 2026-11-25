package app.antresol.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.ApiException
import app.antresol.core.model.ChannelPrefs
import app.antresol.core.model.Me
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairDevice
import app.antresol.core.model.PairLookup
import app.antresol.core.model.Profile
import app.antresol.core.model.UiLanguage
import app.antresol.core.session.AuthState
import app.antresol.core.session.StoredSession
import app.antresol.fixtures.FakeServer
import app.antresol.fixtures.Finding
import app.antresol.fixtures.ScreenAudit
import app.antresol.fixtures.auditReportOnly
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.ui.pairing.PairApprover
import app.antresol.mobile.ui.pairing.PairTvScreen
import app.antresol.mobile.ui.pairing.PairTvViewModel
import app.antresol.mobile.ui.shell.AccountSheet
import app.antresol.mobile.ui.shell.SignOutDialog
import app.antresol.ui.channels.ChannelPrefsApi
import app.antresol.ui.channels.ChannelPrefsModel
import app.antresol.ui.language.UiLanguageApi
import app.antresol.ui.language.UiLanguageModel
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Every phone settings page, sheet and dialog in both languages, at 360 dp and 412 dp wide (and one tablet
 * width), at a font scale of 1.0 and 1.3: shots to `mobile/build/screenshots/settings-audit/phone/<config>/`
 * and the findings of [ScreenAudit] (a text cut with «…», clipped, over another, outside the window, a key
 * shown as text, a missing translation). `SETTINGS_AUDIT_REPORT=1` only reports; otherwise a finding fails.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h780dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneSettingsAuditTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()

    private enum class Scene(val tablet: Boolean = false) {
        SettingsTop(tablet = true),
        SettingsMid,
        SettingsDevices(tablet = true),
        SettingsKid,
        SettingsLongNames,
        DialogQuality,
        DialogRevoke,
        AccountSheet,
        AccountSheetKid,
        DialogSignOut,
        PairScanNoCamera,
        PairScan,
        PairInvalidCode,
        PairConfirm,
        PairConfirmRemote,
        PairDoneApproved,
        PairDoneDeclined,
    }

    private class Languages(private val effective: String) : UiLanguageApi {
        override suspend fun get() = UiLanguage("", effective)

        override suspend fun set(language: String) = UiLanguage(language, effective)
    }

    private class Prefs : ChannelPrefsApi {
        override suspend fun get() = ChannelPrefs(favoritesFirst = true)

        override suspend fun set(prefs: ChannelPrefs) = prefs
    }

    /** A TV that asks to sign in, answered the way the server does. [remote]: from outside the home network. */
    private class Approver(private val remote: Boolean, private val failApprove: Boolean = false) : PairApprover {
        override suspend fun lookup(code: String) = PairLookup(code, PairDevice("Living room", "Android TV (BRAVIA)", "0.9.0"), remote)

        override suspend fun approve(code: String, userId: Long): PairApproved {
            if (failApprove) throw IOException("offline")
            return PairApproved(ok = true, userId = userId)
        }

        override suspend fun deny(code: String) {}

        override suspend fun profiles() = listOf(
            Profile(1, "Alex", "grad-1", hasPassword = true),
            Profile(2, "Kiddo", "grad-4"),
            Profile(3, "A Profile With a Rather Long Name Indeed", "grad-6"),
        )
    }

    private lateinit var session: StoredSession

    private fun signIn() {
        fake.start()
        val manager = app.session
        runBlocking {
            manager.restore()
            if (manager.state.value !is AuthState.NoServer) manager.changeServer()
            manager.connect(fake.baseUrl)
            manager.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        session = (manager.state.value as AuthState.SignedIn).session
    }

    @After
    fun tearDown() = fake.shutdown()

    @Composable
    private fun Content(scene: Scene, language: String) {
        val kid = scene == Scene.SettingsKid || scene == Scene.AccountSheetKid
        val me = remember(scene) {
            Me(
                id = 1,
                name = if (scene == Scene.SettingsLongNames) "Alexandra Konstantinopolskaya-Smith" else "Alex",
                avatar = "grad-3", isAdmin = !kid, isKid = kid,
            )
        }
        val client = remember { app.session.requireClient() }
        AntresolTheme {
            Box(Modifier.fillMaxSize().background(AntresolColors.Background), contentAlignment = Alignment.TopStart) {
                key(scene) {
                    when (scene) {
                        Scene.SettingsTop, Scene.SettingsMid, Scene.SettingsDevices, Scene.SettingsKid, Scene.SettingsLongNames,
                        Scene.DialogQuality, Scene.DialogRevoke,
                        -> SettingsScreen(
                            viewModel = remember { SettingsViewModel(client) },
                            settings = app.settings,
                            session = session.copy(userName = me.name),
                            user = me,
                            onBack = {},
                            onSwitchProfile = {},
                            onSignOut = {},
                            onChangeServer = {},
                            onPairTv = {},
                            language = remember { UiLanguageModel(Languages(language), app.settings) {} },
                            channelPrefs = remember { ChannelPrefsModel(Prefs()) },
                        )
                        Scene.AccountSheet, Scene.AccountSheetKid -> AccountSheet(
                            session = session.copy(userName = me.name), user = me,
                            onDismiss = {}, onSwitchProfile = {}, onSignOut = {}, onChangeServer = {}, onSettings = {},
                        )
                        Scene.DialogSignOut -> SignOutDialog(onConfirm = {}, onDismiss = {})
                        Scene.PairScanNoCamera, Scene.PairScan, Scene.PairInvalidCode,
                        Scene.PairConfirm, Scene.PairConfirmRemote, Scene.PairDoneApproved, Scene.PairDoneDeclined,
                        -> PairTvScreen(
                            viewModel = remember {
                                PairTvViewModel(Approver(remote = scene == Scene.PairConfirmRemote, failApprove = scene == Scene.PairConfirmRemote), "srv", 1, isAdmin = true)
                            },
                            ownName = me.name,
                            ownAvatar = me.avatar,
                            onBack = {},
                            cameraAllowed = scene != Scene.PairScanNoCamera,
                            scanner = { _, modifier -> Box(modifier.background(AntresolColors.FillSecondary)) },
                        )
                    }
                }
            }
        }
    }

    private lateinit var audit: ScreenAudit

    private fun settle(until: SemanticsMatcher? = null, ms: Long = 4_000) {
        val end = System.currentTimeMillis() + ms
        audit.settle(4)
        while (until != null && System.currentTimeMillis() < end && compose.onAllNodes(until, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            audit.settle(2)
        }
    }

    /** A control of the settings list, scrolled into view first (the list composes only what is near). */
    private fun tapInSettings(tag: String) {
        compose.onNode(hasTestTag("settings")).performScrollToNode(hasTestTag(tag))
        compose.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        settle()
    }

    /** A control of a page that scrolls as a whole (the TV pairing page). */
    private fun tap(tag: String) {
        compose.onAllNodes(hasTestTag(tag)).onFirst().performScrollTo().performClick()
        settle()
    }

    private fun scrollSettingsTo(text: String) {
        compose.onNode(hasTestTag("settings")).performScrollToNode(hasText(text))
        settle()
    }

    /** What the scene needs done before its shot: a scroll, a tap, a typed code. */
    private fun drive(scene: Scene, language: String) {
        val ru = language == "ru"
        when (scene) {
            Scene.SettingsTop, Scene.SettingsKid, Scene.SettingsLongNames, Scene.AccountSheet, Scene.AccountSheetKid -> Unit
            Scene.SettingsMid -> scrollSettingsTo(if (ru) "Как у профиля" else "Same as profile")
            Scene.SettingsDevices -> scrollSettingsTo(if (ru) "Версия приложения" else "App Version")
            Scene.DialogQuality -> tapInSettings("settings-quality")
            Scene.DialogRevoke -> tapInSettings("revoke-2")
            Scene.DialogSignOut -> Unit
            Scene.PairScanNoCamera, Scene.PairScan -> Unit
            Scene.PairInvalidCode -> {
                compose.onNodeWithTag("pair-code-field").performScrollTo().performTextInput("X")
                compose.onAllNodes(hasText(if (ru) "Продолжить" else "Continue")).onFirst().performScrollTo().performClick()
                settle()
            }
            Scene.PairConfirm, Scene.PairConfirmRemote, Scene.PairDoneApproved, Scene.PairDoneDeclined -> {
                compose.onNodeWithTag("pair-code-field").performScrollTo().performTextInput("ABC234")
                compose.onAllNodes(hasText(if (ru) "Продолжить" else "Continue")).onFirst().performScrollTo().performClick()
                settle(hasTestTag("pair-approve"))
                if (scene == Scene.PairConfirmRemote) { tap("pair-approve"); settle(hasTestTag("pair-error")) }
                if (scene == Scene.PairDoneApproved) { tap("pair-approve"); settle(hasTestTag("pair-done")) }
                if (scene == Scene.PairDoneDeclined) { tap("pair-deny"); settle(hasTestTag("pair-done")) }
            }
        }
    }

    private fun isDialogScene(scene: Scene) = scene.name.startsWith("Dialog") || scene.name.startsWith("AccountSheet")

    @Test
    fun everySettingsSceneAt360InEnglish() = everyScene("en", "360", tablet = false)

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-port-xxhdpi")
    fun everySettingsSceneAt412InEnglish() = everyScene("en", "412", tablet = false)

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp-port-xhdpi")
    fun everySettingsSceneOnATabletInEnglish() = everyScene("en", "800", tablet = true)

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-port-xxhdpi")
    fun everySettingsSceneAt360InRussian() = everyScene("ru", "360", tablet = false)

    @Test
    @Config(qualifiers = "ru-w412dp-h915dp-port-xxhdpi")
    fun everySettingsSceneAt412InRussian() = everyScene("ru", "412", tablet = false)

    @Test
    @Config(qualifiers = "ru-w800dp-h1280dp-port-xhdpi")
    fun everySettingsSceneOnATabletInRussian() = everyScene("ru", "800", tablet = true)

    private fun everyScene(language: String, width: String, tablet: Boolean) {
        signIn()
        val out = File("build/screenshots/settings-audit/phone")
        val problems = ArrayList<String>()
        val devicesNormal = fake.devicesJson
        val devicesLong =
            """{"items":[{"id":1,"name":"Pixel 8 Pro of the Whole Family Upstairs","model":"Pixel 8 Pro","version":"0.9.0","lastSeen":"2026-11-25T10:00:00Z","current":true},""" +
                """{"id":2,"name":"A Television in the Living Room Downstairs","model":"Android TV (BRAVIA 4K GB)","version":"0.9.0","lastSeen":"2026-11-24T20:00:00Z"}]}"""
        // Names of devices, versions and the like are data of the server, not texts of the app.
        val data = { text: String ->
            text.startsWith("Pixel") || text.startsWith("Living room") || text.startsWith("A Television") || text.startsWith("Android TV") ||
                text.contains("Alex") || text.startsWith("Kiddo") || text.startsWith("A Profile With") || text.startsWith("v0.9") ||
                text.contains("0.9.0") || text.contains("1.5.0") || text.startsWith("Antresol") ||
                text == "Русский" || text == "English"
        }
        // Names a person typed, made long on purpose: they may be shortened with «…», nothing else may.
        val longNames = { text: String ->
            text.startsWith("Pixel 8 Pro of") || text.startsWith("A Television in") || text.startsWith("A Profile With") ||
                text.startsWith("Alexandra Konstantinopolskaya")
        }
        for (scale in listOf(1.0f, 1.3f)) {
            val config = "$language-$width-fs${(scale * 10).toInt()}"
            audit = ScreenAudit(compose, File(out, config))
            app.settings.profileLanguage = language
            // One window per scene: a page left and entered again was seen to keep a test waiting.
            for (scene in Scene.entries) {
                if (tablet && !scene.tablet) continue
                fake.devicesJson = if (scene == Scene.SettingsLongNames || scene == Scene.DialogRevoke) devicesLong else devicesNormal
                val scenario = audit.launch(language, scale) { Content(scene, language) }
                val activity = scenario.get()
                val needsDevices = scene.name.startsWith("Settings") || scene == Scene.DialogQuality || scene == Scene.DialogRevoke
                settle(if (needsDevices) hasText("1.5.0") else null)
                try {
                    drive(scene, language)
                } catch (e: AssertionError) {
                    audit.findings += Finding(scene.name, "driver-failed", "", e.message.orEmpty().lines().first())
                }
                if (isDialogScene(scene)) {
                    if (!audit.dialogShot(scene.name)) audit.findings += Finding(scene.name, "no-dialog", "")
                } else {
                    audit.shot(scene.name, activity)
                }
                audit.report(scene.name, language, allowed = data, mayCut = longNames, lastWindowOnly = isDialogScene(scene))
                scenario.pause().stop().destroy()
            }
            problems += audit.write().lines().filter { it.isNotBlank() }.map { "$config: $it" }
        }
        File(out, "findings-$language-$width.tsv").writeText(problems.joinToString("\n") + "\n")
        if (!auditReportOnly()) assertTrue("Findings:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
