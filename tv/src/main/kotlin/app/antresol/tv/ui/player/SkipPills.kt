package app.antresol.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.playback.SkipNote
import app.antresol.core.playback.SkipPill
import app.antresol.core.playback.SkipSegments
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** The skip state the playing overlay draws: the pill of the segment playing and the note of an automatic skip. */
internal class SkipOverlay(
    val pill: SkipPill?,
    val note: SkipNote?,
    val onPress: () -> Unit,
    val onWatchCredits: () -> Unit,
    val onUndo: () -> Unit,
) {
    /** The pill shows now: a skip pill alone for its first 8 s, then with the controls; «Следующая серия» always. */
    fun pillShown(controlsVisible: Boolean): Boolean = when (pill) {
        is SkipPill.Next -> true
        is SkipPill.Skip -> pill.alone || controlsVisible
        null -> false
    }

    /**
     * What takes focus with the controls hidden (OK presses it): a skip pill within its first 8 s,
     * changes when another one shows. «Следующая серия» and the note of an automatic skip never take it,
     * so OK still pauses while they are up; they are reached with the controls (Up from the seek bar).
     */
    fun focusKey(controlsVisible: Boolean): String? = when (pill) {
        is SkipPill.Skip -> if (pillShown(controlsVisible)) "skip:${pill.kind}" else null
        else -> null
    }

    /** What is on screen now (a pill or the note), for when it goes: changes when another one shows. */
    fun shownKey(controlsVisible: Boolean): String? = when {
        pillShown(controlsVisible) -> when (pill) {
            is SkipPill.Skip -> "skip:${pill.kind}"
            else -> "next"
        }
        note != null -> "note"
        else -> null
    }
}

/** Test tags of the skip pills. */
internal object SkipTags {
    const val SKIP = "player-skip"
    const val NEXT = "player-skip-next"
    const val WATCH_CREDITS = "player-skip-watch-credits"
    const val UNDO = "player-skip-undo"
}

/**
 * The skip pills over the video, bottom right: «Пропустить пересказ / вступление / титры»,
 * «Смотреть титры» + «Следующая серия» (filling while it counts down), and the note of an automatic
 * skip with «Вернуть». [focus] goes on the skip pill OK should press ([SkipOverlay.focusKey]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SkipPills(skip: SkipOverlay, controlsVisible: Boolean, focus: FocusRequester, modifier: Modifier = Modifier) {
    val pillShown = skip.pillShown(controlsVisible)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        skip.note?.let { note ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .background(AntresolColors.Glass, AntresolShapes.pill)
                    .padding(start = 22.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    stringResource(if (note.kind == SkipSegments.RECAP) R.string.player_recap_skipped else R.string.player_intro_skipped),
                    style = Antresol.typography.body,
                )
                PillButton(
                    text = stringResource(R.string.player_skip_undo),
                    onClick = skip.onUndo,
                    modifier = Modifier.testTag(SkipTags.UNDO),
                )
            }
        }
        if (!pillShown) return@Column
        when (val pill = skip.pill) {
            is SkipPill.Skip -> PillButton(
                text = stringResource(
                    when (pill.kind) {
                        SkipSegments.RECAP -> R.string.player_skip_recap
                        SkipSegments.INTRO -> R.string.player_skip_intro
                        else -> R.string.player_skip_credits
                    },
                ),
                onClick = skip.onPress,
                icon = AntresolIcons.SkipAhead,
                modifier = Modifier.focusRequester(focus).testTag(SkipTags.SKIP),
            )
            // Focus entering the pair (Up from the seek bar) lands on «Следующая серия», not on its neighbour.
            is SkipPill.Next -> Row(
                modifier = Modifier.focusProperties { enter = { focus } }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PillButton(
                    text = stringResource(R.string.player_watch_credits),
                    onClick = skip.onWatchCredits,
                    modifier = Modifier.testTag(SkipTags.WATCH_CREDITS),
                )
                CountdownPill(
                    text = stringResource(R.string.player_next_episode),
                    fill = pill.fill,
                    onClick = skip.onPress,
                    modifier = Modifier.focusRequester(focus).testTag(SkipTags.NEXT),
                )
            }
            null -> Unit
        }
    }
}

/** The white «Следующая серия» pill of [PillButton]'s look, darkening from the left as the countdown runs ([fill], 0…1). */
@Composable
private fun CountdownPill(text: String, fill: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AntresolColors.Label.copy(alpha = 0.9f),
            contentColor = AntresolColors.LabelInverse,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.35f), elevation = 12.dp),
        ),
    ) {
        Box(Modifier.matchParentSize().clip(AntresolShapes.pill)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.coerceIn(0f, 1f))
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 20.dp, end = 26.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AntresolIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
            Text(text, style = Antresol.typography.button, color = LocalContentColor.current, maxLines = 1)
        }
    }
}
