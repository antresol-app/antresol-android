package app.antresol.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.mobile.ui.components.TimeSlider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The seek slider tells a screen reader the time, not a percentage. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en")
class TimeSliderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saysThePositionAsTime() {
        compose.setContent { TimeSlider(positionMs = 754_000, durationMs = 3_720_000, onSeek = {}) }
        compose.onNodeWithTag("time-slider")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:34 of 1:02:00"))
    }
}
