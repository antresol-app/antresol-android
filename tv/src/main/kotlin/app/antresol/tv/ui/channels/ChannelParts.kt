package app.antresol.tv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.model.ChannelCategory
import app.antresol.ui.channels.channelFilterTitle
import app.antresol.core.channels.Channels
import app.antresol.core.model.ChannelProgram
import app.antresol.ui.R
import app.antresol.ui.channels.programClock
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** A live progress capsule in [color] on a translucent track of it (readable on white focus too). */
@Composable
fun LiveProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(AntresolShapes.pill)
            .background(color.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(AntresolShapes.pill)
                .background(color),
        )
    }
}

/** The small star after a favourite channel's name. */
@Composable
fun FavoriteMark(tint: Color = AntresolColors.Orange, modifier: Modifier = Modifier) {
    Icon(AntresolIcons.StarFilled, contentDescription = null, tint = tint, modifier = modifier.size(11.dp))
}

/** «OK» and what it does: a remote key hint. */
@Composable
fun KeyHint(key: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            key,
            style = Antresol.typography.badge,
            color = AntresolColors.Label,
            modifier = Modifier
                .border(1.dp, AntresolColors.LabelTertiary, AntresolShapes.badge)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Text(label, style = Antresol.typography.caption, maxLines = 1)
    }
}

/** «21:10–22:40 · 90 мин», with the live progress («В эфире до 22:40») while on air. */
@Composable
fun ProgramTime(program: ChannelProgram, nowMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val minutes = Channels.displayMinutes(program)
    val time = listOfNotNull(
        stringResource(R.string.tv_time, programClock(context, program.start), programClock(context, program.end)),
        minutes?.let { stringResource(R.string.tv_minutes, it) },
    ).joinToString(" · ")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(time, style = Antresol.typography.bodyEmphasis)
        if (Channels.isOnAir(program, nowMs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveProgressBar(Channels.progress(program, nowMs), AntresolColors.Label, Modifier.weight(1f))
                Text(stringResource(R.string.tv_on_air_until, programClock(context, program.end)), style = Antresol.typography.caption)
            }
        }
    }
}

/** «Комедия, приключения · 1993 · 12+». */
fun programFacts(program: ChannelProgram): String = listOfNotNull(
    program.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar(Char::titlecase),
    program.year?.toString(),
    program.rating.ifBlank { null },
).joinToString(" · ")

/** The list a filter shows, by name: «ТВ», «Избранное» or the category ([categories]: the server's names). */
@Composable
fun filterTitle(filter: ChannelFilter, categories: List<ChannelCategory> = emptyList()): String = channelFilterTitle(filter, categories)
