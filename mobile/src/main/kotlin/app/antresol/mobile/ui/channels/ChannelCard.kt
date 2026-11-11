package app.antresol.mobile.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.shadow
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
import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.ChannelLogoBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/**
 * A channel card, like the web's: the programme on air (its backdrop, or the channel's logo filling
 * the card), the small logo in the corner, the live progress at the bottom, then «10 · Comedy», the
 * programme's title and «Next at 23:15 — …» ([showNext]). Pass [width] in rows; grids fill their
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
    val art = program?.let { urls?.image(it.backdrop.ifEmpty { it.poster }) }
    val label = stringResource(R.string.tv_channel_number, channel.number, channel.name)
    val line = program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing)
    val nextLine = next?.let { stringResource(R.string.tv_next_at_long, programClock(context, it.start), it.title) }
    val description = listOfNotNull(stringResource(R.string.tv_watch, channel.name), line, nextLine).joinToString(". ")
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(
        modifier = sized
            .clip(AntresolShapes.smallCard)
            .focusClickable(AntresolShapes.smallCard, role = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val frame = Modifier
            .fillMaxWidth()
            .aspectRatio(AntresolMotion.BACKDROP_ASPECT)
        if (art != null) {
            ArtworkImage(url = art, title = program?.title.orEmpty(), shape = AntresolShapes.card, modifier = frame) {
                ChannelLogoBadge(
                    name = channel.name,
                    logo = channel.logo,
                    // Small cards (the player's strip) keep a readable logo.
                    width = if (width != null && width < 200.dp) 52.dp else 56.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .shadow(6.dp, AntresolShapes.smallCard),
                )
                LiveProgress(program, nowMs)
            }
        } else {
            Box(frame) {
                ChannelLogo(name = channel.name, logo = channel.logo, shape = AntresolShapes.card, filled = true, modifier = Modifier.fillMaxSize())
                LiveProgress(program, nowMs)
            }
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
            Text(text = label, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
