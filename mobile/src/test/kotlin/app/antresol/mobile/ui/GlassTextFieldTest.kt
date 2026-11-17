package app.antresol.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.mobile.ui.components.FormError
import app.antresol.mobile.ui.components.GlassTextField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a screen reader gets from a sign-in form: the field keeps its name, the error is announced. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GlassTextFieldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFieldIsNamedByItsPlaceholderAlsoWhenFilled() {
        compose.setContent {
            Column {
                GlassTextField(value = "", onValueChange = {}, placeholder = "Password", modifier = Modifier.testTag("empty"))
                GlassTextField(value = "secret", onValueChange = {}, placeholder = "Password", modifier = Modifier.testTag("filled"))
            }
        }
        compose.onNodeWithTag("empty").assertContentDescriptionEquals("Password")
        compose.onNodeWithTag("filled").assertContentDescriptionEquals("Password")
        // The visible placeholder is not a second thing to read.
        compose.onAllNodesWithText("Password", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theErrorIsALiveRegion() {
        compose.setContent { FormError("Wrong password") }
        compose.onNodeWithText("Wrong password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
