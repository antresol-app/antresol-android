package app.antresol.mobile.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Width of the programme still in a channel row. */
private val RowStillWidth = 112.dp

/** Width of the number column: three tabular digits. */
private val NumberWidth = 30.dp

/**
 * One channel of the list, like a television's channel list (Android TV Live Channels, TiviMate):
 * its number, the still of the programme on air, the channel's name (with a star when it is a
 * favourite), the programme, a live progress bar with «до 21:40» and «Далее 21:40 Название».
 * [last] marks the profile's last channel. A tap tunes in; a long press offers the favourite.
 */
@Composable
fun ChannelRow(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    last: Boolean = false,
) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val next = channel.next?.takeIf { it != program }
    val title = program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing)
    val until = program?.let { stringResource(R.string.tv_until, programClock(context, it.end)) }
    val nextLine = next?.let { stringResource(R.string.tv_next_short, programClock(context, it.start), Channels.programLine(it)) }
    val favoriteLabel = stringResource(if (channel.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add)
    val description = listOfNotNull(
        stringResource(R.string.tv_channel_number, channel.number, channel.name),
        stringResource(R.string.tv_favorite).takeIf { channel.favorite },
        title,
        until,
        nextLine,
        stringResource(R.string.tv_last_channel).takeIf { last },
    ).joinToString(". ")
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageGutter - 8.dp)
                .clip(AntresolShapes.control)
                .background(if (last) AntresolColors.FillQuaternary else AntresolColors.Background)
                .focusClickable(AntresolShapes.control, onLongClick = { menu = true }, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true })
                }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChannelNumber(channel.number, Modifier.width(NumberWidth), highlighted = last)
            ProgrammeStill(
                url = urls?.image(programmeArt(program)),
                modifier = Modifier
                    .width(RowStillWidth)
                    .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        channel.name,
                        style = Antresol.typography.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.favorite) FavoriteMark()
                }
                Text(title, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (program != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressBar(Channels.progress(program, nowMs), Modifier.weight(1f))
                        Text(until.orEmpty(), style = Antresol.typography.caption, maxLines = 1)
                    }
                }
                if (nextLine != null) {
                    Text(
                        nextLine,
                        style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            containerColor = AntresolColors.BackgroundElevated,
            shape = AntresolShapes.control,
        ) {
            DropdownMenuItem(
                text = { Text(favoriteLabel, style = Antresol.typography.body) },
                leadingIcon = {
                    Icon(
                        if (channel.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                        contentDescription = null,
                        tint = AntresolColors.Label,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menu = false
                    onToggleFavorite()
                },
            )
        }
    }
}

/** A channel number: large tabular digits in the secondary colour (the accent for the last channel). */
@Composable
fun ChannelNumber(number: Int, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    Text(
        number.toString(),
        style = Antresol.typography.sectionTitle.copy(
            color = if (highlighted) AntresolColors.Accent else AntresolColors.LabelSecondary,
            fontFeatureSettings = "tnum",
        ),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The small star after a favourite channel's name. */
@Composable
fun FavoriteMark(modifier: Modifier = Modifier) {
    Icon(
        AntresolIcons.StarFilled,
        contentDescription = null,
        tint = AntresolColors.Orange,
        modifier = modifier.size(12.dp),
    )
}
