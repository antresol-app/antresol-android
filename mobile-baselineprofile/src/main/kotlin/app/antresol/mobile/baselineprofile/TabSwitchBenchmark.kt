package app.antresol.mobile.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone app's start and tab switches, without ahead-of-time compilation and with the Baseline Profile.
 * Run: `./gradlew :mobile-baselineprofile:connectedBenchmarkReleaseAndroidTest` (NOTES.md).
 */
@RunWith(AndroidJUnit4::class)
class TabSwitchBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun tabSwitchesNoCompilation() = tabSwitches(CompilationMode.None())

    @Test
    fun tabSwitchesBaselineProfile() = tabSwitches(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun tabSwitches(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            signInIfNeeded()
            waitForHome()
        },
    ) {
        repeat(2) { goRoundTheTabs() }
    }

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 8,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        signInIfNeeded()
        waitForHome()
    }
}
