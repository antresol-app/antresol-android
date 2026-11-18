package app.antresol.tv.autostart

import app.antresol.tv.settings.TvPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The autostart's rules: what makes it skip, when it launches and how long it keeps trying. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutostartTest {
    private val on = TvPreferences(autostart = true)

    private fun skip(
        prefs: TvPreferences = on,
        trigger: AutostartTrigger = AutostartTrigger.Boot,
        permission: Boolean = true,
        visible: Boolean = false,
        interactive: Boolean = true,
    ) = AutostartPolicy.skipReason(prefs, trigger, permission, visible, interactive)

    @Test
    fun offByDefault() {
        assertFalse(TvPreferences().autostart)
        assertEquals(AutostartPolicy.Skip.Off, skip(prefs = TvPreferences()))
    }

    @Test
    fun opensWhenOnWithThePermission() {
        assertNull(skip())
        assertNull(skip(trigger = AutostartTrigger.Wake))
    }

    @Test
    fun wakeNeedsItsOwnSwitch() {
        val bootOnly = on.copy(autostartOnWake = false)
        assertNull(skip(bootOnly, AutostartTrigger.Boot))
        assertEquals(AutostartPolicy.Skip.WakeOff, skip(bootOnly, AutostartTrigger.Wake))
    }

    @Test
    fun withoutThePermissionNothingIsTried() {
        assertEquals(AutostartPolicy.Skip.NoPermission, skip(permission = false))
    }

    @Test
    fun anAppInFrontIsLeftAlone() {
        assertEquals(AutostartPolicy.Skip.AlreadyVisible, skip(visible = true))
    }

    @Test
    fun aScreenThatWentOffAgainIsNotAWake() {
        assertEquals(AutostartPolicy.Skip.ScreenOff, skip(trigger = AutostartTrigger.Wake, interactive = false))
        // A box that boots with its display asleep still opens (the screen state is only for a wake).
        assertNull(skip(trigger = AutostartTrigger.Boot, interactive = false))
    }

    @Test
    fun theWatchingServiceRunsOnlyForAWakeThatCanOpenTheApp() {
        assertTrue(AutostartPolicy.wakeWatchWanted(on, permissionGranted = true))
        assertFalse(AutostartPolicy.wakeWatchWanted(on, permissionGranted = false))
        assertFalse(AutostartPolicy.wakeWatchWanted(on.copy(autostartOnWake = false), permissionGranted = true))
        assertFalse(AutostartPolicy.wakeWatchWanted(TvPreferences(), permissionGranted = true))
    }

    @Test
    fun theOverlayPermissionMattersFromAndroid10() {
        assertFalse(AutostartPolicy.needsOverlayPermission(28))
        assertTrue(AutostartPolicy.needsOverlayPermission(29))
        assertTrue(AutostartPolicy.needsOverlayPermission(36))
    }

    @Test
    fun targetKeysSurviveAndUnknownOnesFallBack() {
        AutostartTarget.entries.forEach { assertEquals(it, AutostartTarget.fromKey(it.key)) }
        assertEquals(AutostartTarget.Last, AutostartTarget.fromKey(null))
        assertEquals(AutostartTarget.Last, AutostartTarget.fromKey("home"))
    }

    @Test
    fun aRequestGoesStale() {
        val request = AutostartRequest(AutostartTarget.Tv, atMs = 1_000)
        assertTrue(request.fresh(1_000))
        assertTrue(request.fresh(1_000 + AutostartRequest.MAX_AGE_MS))
        assertFalse(request.fresh(1_001 + AutostartRequest.MAX_AGE_MS))
        assertFalse("a clock that went back", request.fresh(500))
    }

    /** A device on which the app comes to the front when it is launched (or never does, with [covered]). */
    private inner class Device(
        var prefs: TvPreferences = on.copy(autostartTarget = AutostartTarget.Channel.key),
        var covered: Boolean = false,
        var coveredFor: Int = 0,
        var permission: Boolean = true,
        var interactive: Boolean = true,
    ) {
        var visible = false
        val launched = mutableListOf<AutostartTarget>()
        val runner = AutostartRunner(
            prefs = { prefs },
            permissionGranted = { permission },
            appVisible = { visible },
            interactive = { interactive },
            launch = { target ->
                launched += target
                // The home screen that starts after us covers the first launches.
                if (!covered && launched.size > coveredFor) visible = true
            },
        )
    }

    @Test
    fun bootWaitsForTheHomeScreenThenLaunchesTheChosenTarget() = runTest {
        val device = Device()
        val outcome = device.runner.run(AutostartTrigger.Boot)
        assertEquals(AutostartOutcome.Opened(1), outcome)
        assertEquals(listOf(AutostartTarget.Channel), device.launched)
        assertTrue("not before the home screen has started", currentTime >= AutostartPolicy.BOOT_DELAY_MS)
    }

    @Test
    fun aLaunchThatTheHomeScreenCoversIsRepeated() = runTest {
        val device = Device(coveredFor = 1)
        assertEquals(AutostartOutcome.Opened(2), device.runner.run(AutostartTrigger.Boot))
        assertEquals(2, device.launched.size)
    }

    @Test
    fun itGivesUpAfterTheAttempts() = runTest {
        val device = Device(covered = true)
        assertEquals(AutostartOutcome.GaveUp, device.runner.run(AutostartTrigger.Wake))
        assertEquals(AutostartPolicy.ATTEMPTS, device.launched.size)
    }

    @Test
    fun aWakeWithTheAppAlreadyInFrontLaunchesNothing() = runTest {
        val device = Device().apply { visible = true }
        assertEquals(AutostartOutcome.Skipped(AutostartPolicy.Skip.AlreadyVisible), device.runner.run(AutostartTrigger.Wake))
        assertTrue(device.launched.isEmpty())
        assertEquals(AutostartPolicy.WAKE_DELAY_MS, currentTime)
    }

    @Test
    fun switchingItOffWhileItWaitsCancelsTheLaunch() = runTest {
        var prefs = on
        val launched = mutableListOf<AutostartTarget>()
        val runner = AutostartRunner(
            prefs = { prefs },
            permissionGranted = { true },
            appVisible = { false },
            interactive = { true },
            launch = { launched += it },
        )
        // The settings are read after the wait, not before it.
        prefs = TvPreferences()
        assertEquals(AutostartOutcome.Skipped(AutostartPolicy.Skip.Off), runner.run(AutostartTrigger.Boot))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun takingThePermissionBackStopsIt() = runTest {
        val device = Device(permission = false)
        assertEquals(AutostartOutcome.Skipped(AutostartPolicy.Skip.NoPermission), device.runner.run(AutostartTrigger.Boot))
        assertTrue(device.launched.isEmpty())
    }
}
