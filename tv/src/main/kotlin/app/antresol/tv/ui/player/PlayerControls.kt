package app.antresol.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.ui.components.opticalOffset
import app.antresol.ui.format.Durations
import app.antresol.ui.playback.PlayerMenu
import app.antresol.ui.playback.VideoPlayerViewModel
import app.antresol.ui.playback.playerMenu
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/**
 * The focusable progress bar: Left/Right seek (10 s by default), OK toggles pause. It thickens and shows a
 * knob when focused. Elapsed time on the left, remaining time on the right.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    backStepMs: Long = VideoPlayerViewModel.SEEK_STEP_MS,
    forwardStepMs: Long = VideoPlayerViewModel.SEEK_STEP_MS,
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .height(22.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionLeft -> { if (down) onSeek(-backStepMs); true }
                        Key.DirectionRight -> { if (down) onSeek(forwardStepMs); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (down) onToggle(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackHeight = if (focused) 8.dp else 5.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(AntresolShapes.pill)
                    .background(AntresolColors.FillPrimary),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(buffered)
                        .fillMaxHeight()
                        .background(AntresolColors.Label.copy(alpha = 0.3f)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(if (focused) AntresolColors.Label else AntresolColors.Label.copy(alpha = 0.85f)),
                )
            }
            if (focused) {
                val knob = 18.dp
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - knob / 2)
                        .size(knob)
                        .background(AntresolColors.Label, CircleShape),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Durations.clock(positionMs), style = Antresol.typography.caption)
            Spacer(Modifier.weight(1f))
            Text("-" + Durations.clock((durationMs - positionMs).coerceAtLeast(0)), style = Antresol.typography.caption)
        }
    }
}

/**
 * A round glass icon button of the player's control row, like the Apple TV app's transport-bar
 * buttons and the live player's banner: glass with a white glyph at rest, white with a black glyph,
 * lifted and glowing when focused (the TV app's focus look). Its [label] is the accessibility name
 * and shows as a caption under the button only while it is focused; the caption takes no width, so
 * the row does not shift as focus moves.
 */
@Composable
internal fun PlayerIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .size(PlayerButtonSize)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AntresolColors.Glass,
                contentColor = AntresolColors.Label,
                focusedContainerColor = AntresolColors.Label,
                focusedContentColor = AntresolColors.LabelInverse,
                pressedContainerColor = AntresolColors.LabelSecondary,
                pressedContentColor = AntresolColors.LabelInverse,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.35f), 12.dp)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
                    .opticalOffset(icon),
            )
        }
        Box(Modifier.height(16.dp)) {
            if (focused) {
                Text(
                    label,
                    style = Antresol.typography.caption.copy(color = AntresolColors.Label),
                    maxLines = 1,
                    modifier = Modifier.layout { measurable, _ ->
                        // As wide as it needs, centred under the button, without widening the row.
                        val caption = measurable.measure(Constraints())
                        layout(0, caption.height) { caption.place(-caption.width / 2, 0) }
                    },
                )
            }
        }
    }
}

private val PlayerButtonSize = 48.dp

/** The audio, subtitle or quality choices of the player in an [OptionsPanel]. */
@Composable
fun TrackMenu(menu: PlayerMenu, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val content = playerMenu(menu, viewModel)
    OptionsPanel(title = content.title, options = content.options, onDismiss = onDismiss, note = content.note)
}
