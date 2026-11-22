package app.antresol.ui.ways

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import app.antresol.core.model.AddonWay
import app.antresol.core.model.WayIcon
import app.antresol.core.model.WayRun
import app.antresol.core.model.WayState
import app.antresol.core.ways.WayOffer
import app.antresol.core.ways.WayOutcome
import app.antresol.core.ways.WayPicker
import app.antresol.core.ways.WayPlayback
import app.antresol.core.ways.WayTarget
import app.antresol.core.ways.WaysController
import app.antresol.ui.R
import app.antresol.ui.userMessage

// What the screens read of a [WaysController]. All of them accept null: a page without addons' ways
// draws nothing for them and costs nothing.

/** The ways the title offers. */
@Composable
fun WaysController?.offerState(): WayOffer = this?.offer?.collectAsState()?.value ?: WayOffer()

/** The state of the title's keep ways, by way key. */
@Composable
fun WaysController?.statesState(): Map<String, WayState> = this?.states?.collectAsState()?.value ?: emptyMap()

/** The quick run that waits for the addon: which way, for which title or episode (see [WayRun.isFor]). */
@Composable
fun WaysController?.busyState(): WayRun? = this?.busy?.collectAsState()?.value

/** The [AddonWay.key] remembered for the title as the way «Play» runs, or null (the first way runs). */
@Composable
fun WaysController?.defaultKeyState(): String? = this?.defaultKey?.collectAsState()?.value

/**
 * The line under [way]'s label in the menu of ways: its own `hint`, else a neutral one by its icon (a web stream,
 * or one the app plays); null when there is none.
 */
@Composable
fun wayHint(way: AddonWay): String? = way.hint?.takeIf { it.isNotBlank() } ?: when (way.icon) {
    WayIcon.GLOBE -> stringResource(R.string.way_hint_globe)
    WayIcon.PLAY -> stringResource(R.string.way_hint_play)
    else -> null
}

/** The open choice sheet. */
@Composable
fun WaysController?.pickerState(): WayPicker? = this?.picker?.collectAsState()?.value

/**
 * Keeps [target] attached to this controller while the page shows it, and hands what a run came to
 * to the page: [onPlay] opens a player, [onText] shows a sentence (the server's own words for a done
 * way, or why it failed).
 */
@Composable
fun WaysController?.Attach(
    target: WayTarget?,
    resources: Resources,
    onPlay: (WayPlayback) -> Unit,
    onText: (String) -> Unit,
) {
    val controller = this
    val play by rememberUpdatedState(onPlay)
    val text by rememberUpdatedState(onText)
    LaunchedEffect(controller, target) {
        if (controller != null && target != null) controller.attach(target)
    }
    LaunchedEffect(controller) {
        controller?.outcomes?.collect { outcome ->
            when (outcome) {
                is WayOutcome.Play -> play(outcome.playback)
                is WayOutcome.Done -> text(outcome.text)
                is WayOutcome.Failed -> text(outcome.error.userMessage(resources))
            }
        }
    }
}
