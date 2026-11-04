package app.antresol.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antresol.ui.format.Durations
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/**
 * A thin white scrubber with elapsed and remaining time under it. While the thumb is dragged the
 * times follow the finger; [onSeek] fires once, on release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val shown = dragging?.let { (it * duration).toLong() } ?: positionMs.coerceIn(0, duration)
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = dragging ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = AntresolColors.Label,
                activeTrackColor = AntresolColors.Label,
                inactiveTrackColor = AntresolColors.Label.copy(alpha = 0.25f),
                disabledThumbColor = AntresolColors.LabelTertiary,
                disabledActiveTrackColor = AntresolColors.LabelTertiary,
            ),
            thumb = {
                Box(Modifier.size(if (dragging != null) 18.dp else 12.dp).background(AntresolColors.Label, CircleShape))
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = AntresolColors.Label,
                        inactiveTrackColor = AntresolColors.Label.copy(alpha = 0.25f),
                    ),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.fillMaxWidth().height(28.dp).testTag("time-slider"),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Durations.clock(shown), style = Antresol.typography.caption, modifier = Modifier.weight(1f))
            Text("-" + Durations.clock((duration - shown).coerceAtLeast(0)), style = Antresol.typography.caption)
        }
    }
}

/** A round icon button; [filled] draws the white primary variant (play/pause). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    filled: Boolean = false,
    glass: Boolean = false,
    tint: Color = if (filled) AntresolColors.LabelInverse else AntresolColors.Label,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    filled -> AntresolColors.Label
                    glass -> AntresolColors.Glass.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
    }
}

/** "Back 15" / "Forward 30": the circular arrow with the number inside. */
@Composable
fun SkipSecondsButton(
    seconds: Int,
    forward: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    glass: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (glass) AntresolColors.Glass.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MobileIcons.SkipBackCircle,
            contentDescription = null,
            tint = AntresolColors.Label,
            modifier = Modifier.size(size * 0.62f).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            seconds.toString(),
            style = Antresol.typography.caption.copy(color = AntresolColors.Label, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
