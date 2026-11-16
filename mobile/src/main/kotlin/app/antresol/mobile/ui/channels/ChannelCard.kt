package app.antresol.mobile.ui.channels

import app.antresol.ui.channels.ChannelLogo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.channels.programClock
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/**
 * A channel card for rows (Home's «Сейчас в эфире», the player's strip): the still of the
 * programme on air (or the neutral placeholder) with its live progress, then «10 · Comedy» with the
 * star of a favourite, and the programme. No logo tiles. Pass [width] in rows; grids fill their
 * cell. [watching] marks the channel on screen (the player's strip) with the live dot.
 */
@Composable
fun ChannelCard(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    watching: Boolean = false,
    showNext: Boolean = true,
) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val next = channel.next?.takeIf { showNext && it != program }
    val label = stringResource(R.string.tv_channel_number, channel.number, channel.name)
    val line = program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing)
    val nextLine = next?.let { stringResource(R.string.tv_next_short, programClock(context, it.start), it.title) }
    val description = listOfNotNull(stringResource(R.string.tv_watch, channel.name), line, nextLine).joinToString(". ")
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(
        modifier = sized
            .clip(AntresolShapes.smallCard)
            .focusClickable(AntresolShapes.smallCard, role = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ProgrammeStill(
            url = urls?.image(programmeArt(program)),
            shape = AntresolShapes.card,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
        ) {
            LiveProgress(program, nowMs)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (watching) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(AntresolColors.Red, CircleShape),
                )
            }
            ChannelLogo(urls?.image(channel.logoUrl), height = 18.dp)
            Text(text = label, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (channel.favorite) FavoriteMark()
        }
        Text(text = line, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (nextLine != null) {
            Text(
                text = nextLine,
                style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoxScope.LiveProgress(program: ChannelProgram?, nowMs: Long) {
    if (program == null) return
    ProgressBar(
        fraction = Channels.progress(program, nowMs),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(10.dp),
    )
}
