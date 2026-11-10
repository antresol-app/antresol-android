package app.antresol.mobile.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/**
 * A channel card, like the web's: the programme on air (its backdrop, or the channel's logo filling
 * the card), the small logo in the corner, the live progress at the bottom, then «10 · Comedy» and
 * the programme's title. Pass [width] in rows; grids fill their cell.
 */
@Composable
fun ChannelCard(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val art = program?.let { urls?.image(it.backdrop.ifEmpty { it.poster }) }
    val label = stringResource(R.string.tv_channel_number, channel.number, channel.name)
    val line = program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing)
    val description = stringResource(R.string.tv_watch, channel.name) + ". " + line
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
                    width = 56.dp,
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
        Text(
            text = label,
            style = Antresol.typography.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(text = line, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
