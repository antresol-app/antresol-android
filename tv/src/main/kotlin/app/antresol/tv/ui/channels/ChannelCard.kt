package app.antresol.tv.ui.channels

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.CardSizes
import app.antresol.tv.ui.components.CardSurface
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
 * A channel in a row, like the web's channel card: the programme on air (its backdrop, or the
 * channel's logo filling the card), the small logo in the corner, the live progress at the bottom,
 * then «10 · Comedy», the programme's title and «Next at 23:15 — …». [nowMs] drives the progress.
 * [watching] marks the channel on screen (the player's mini-guide) with the live dot; the guide
 * leaves out what is next ([showNext]) to stay low.
 */
@Composable
fun ChannelCard(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    width: Dp = CardSizes.wideWidth,
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
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardSurface(
            onClick = onClick,
            outline = AntresolShapes.card,
            modifier = surfaceModifier
                .fillMaxWidth()
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT)
                .onFocusChanged { focused = it.hasFocus }
                .semantics { contentDescription = description },
        ) {
            if (art != null) {
                ArtworkImage(url = art, title = program?.title.orEmpty(), shape = AntresolShapes.card, modifier = Modifier.fillMaxSize()) {
                    ChannelLogoBadge(
                        name = channel.name,
                        logo = channel.logo,
                        // Smaller cards (the player's mini-guide) keep a readable logo.
                        width = maxOf(width * 0.22f, 52.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .shadow(6.dp, AntresolShapes.smallCard),
                    )
                    LiveProgress(program, nowMs)
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    ChannelLogo(name = channel.name, logo = channel.logo, shape = AntresolShapes.card, filled = true, modifier = Modifier.fillMaxSize())
                    LiveProgress(program, nowMs)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (watching) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(AntresolColors.Red, CircleShape),
                    )
                }
                Text(
                    text = label,
                    style = Antresol.typography.title,
                    color = if (focused) AntresolColors.Label else AntresolColors.Label.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = line, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nextLine != null) {
                Text(
                    text = nextLine,
                    style = Antresol.typography.caption,
                    color = AntresolColors.LabelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}
