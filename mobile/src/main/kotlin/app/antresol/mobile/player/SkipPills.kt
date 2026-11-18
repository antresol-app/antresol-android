package app.antresol.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.playback.SkipNote
import app.antresol.core.playback.SkipPill
import app.antresol.core.playback.SkipSegments
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** Test tags of the skip pills. */
internal object SkipTags {
    const val SKIP = "player-skip"
    const val NEXT = "player-skip-next"
    const val WATCH_CREDITS = "player-skip-watch-credits"
    const val UNDO = "player-skip-undo"
}

/**
 * The skip pills over the video, bottom right (tappable whether or not the controls show):
 * «Пропустить пересказ / вступление / титры» (alone for its first 8 s, then with the controls),
 * «Смотреть титры» + «Следующая серия» filling while it counts down (it takes the place of the
 * controls' Next Episode at the credits), and the note of an automatic skip with «Вернуть».
 */
@Composable
internal fun SkipPills(
    pill: SkipPill?,
    note: SkipNote?,
    controlsVisible: Boolean,
    onPress: () -> Unit,
    onWatchCredits: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShown = when (pill) {
        is SkipPill.Next -> true
        is SkipPill.Skip -> pill.alone || controlsVisible
        null -> false
    }
    if (!pillShown && note == null) return
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        note?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .background(AntresolColors.Glass, AntresolShapes.pill)
                    .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    stringResource(if (it.kind == SkipSegments.RECAP) UiR.string.player_recap_skipped else UiR.string.player_intro_skipped),
                    style = Antresol.typography.body,
                )
                GlassPillButton(stringResource(UiR.string.player_skip_undo), onClick = onUndo, modifier = Modifier.testTag(SkipTags.UNDO))
            }
        }
        if (!pillShown) return@Column
        when (pill) {
            is SkipPill.Skip -> GlassPillButton(
                text = stringResource(
                    when (pill.kind) {
                        SkipSegments.RECAP -> UiR.string.player_skip_recap
                        SkipSegments.INTRO -> UiR.string.player_skip_intro
                        else -> UiR.string.player_skip_credits
                    },
                ),
                icon = AntresolIcons.SkipAhead,
                onClick = onPress,
                modifier = Modifier.testTag(SkipTags.SKIP),
            )
            is SkipPill.Next -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPillButton(stringResource(UiR.string.player_watch_credits), onClick = onWatchCredits, modifier = Modifier.testTag(SkipTags.WATCH_CREDITS))
                CountdownPill(stringResource(UiR.string.player_next_episode), pill.fill, onPress, Modifier.testTag(SkipTags.NEXT))
            }
            null -> Unit
        }
    }
}

/** The white pill of «Следующая серия», darkening from the left as the countdown runs ([fill], 0…1). */
@Composable
private fun CountdownPill(text: String, fill: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(AntresolShapes.pill)
            .background(AntresolColors.Label)
            .focusClickable(AntresolShapes.pill, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.coerceIn(0f, 1f))
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AntresolIcons.Play, contentDescription = null, tint = AntresolColors.LabelInverse, modifier = Modifier.size(20.dp))
            Text(text, style = Antresol.typography.button.copy(color = AntresolColors.LabelInverse), maxLines = 1)
        }
    }
}
