package app.antresol.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the TV app's Baseline Profile (`tv/src/release/generated/baselineProfiles`): the start up to Home, then
 * every tab, scrolling, a title's page and a grid, so their code is compiled ahead of time on install instead of
 * running interpreted the first times. Run: `./gradlew :tv:generateBaselineProfile` (NOTES.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        signInIfNeeded()
        waitForHome()
    }

    @Test
    fun tabsAndPages() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        signInIfNeeded()
        waitForHome()
        repeat(2) { goRoundTheTabs() }
        browse()
    }
}
