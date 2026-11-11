package app.antresol.tv.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.CardSurface
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.ChannelLogoBadge
import app.antresol.ui.channels.LiveBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import java.util.Locale

/** Width of the «Continue» card: a third wider than two row cards, so it reads as the page's lead. */
private val ContinueCardWidth = 392.dp

/**
 * «Continue»: the channel this profile watched last, first on the TV page. A large card with the
 * programme on air (backdrop, logo, live progress) and beside it the eyebrow, the programme's
 * title, the channel, its time and what is next. OK resumes the channel live.
 */
@Composable
fun ContinueChannel(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val next = channel.next?.takeIf { it != program }
    val art = program?.let { urls?.image(it.backdrop.ifEmpty { it.poster }) }
    val label = stringResource(R.string.tv_channel_number, channel.number, channel.name)
    val description = stringResource(R.string.tv_continue_watching, channel.name) + ". " +
        (program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing))
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPaddingH),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardSurface(
            onClick = onClick,
            outline = AntresolShapes.card,
            modifier = surfaceModifier
                .width(ContinueCardWidth)
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT)
                .onFocusChanged { focused = it.hasFocus }
                .semantics { contentDescription = description },
        ) {
            if (art != null) {
                ArtworkImage(url = art, title = program.title, shape = AntresolShapes.card, modifier = Modifier.fillMaxSize()) {
                    ChannelLogoBadge(
                        name = channel.name,
                        logo = channel.logo,
                        width = 84.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .shadow(8.dp, AntresolShapes.smallCard),
                    )
                    if (program != null) Progress(Channels.progress(program, nowMs))
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    ChannelLogo(name = channel.name, logo = channel.logo, shape = AntresolShapes.card, filled = true, modifier = Modifier.fillMaxSize())
                    if (program != null) Progress(Channels.progress(program, nowMs))
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.tv_continue).uppercase(Locale.getDefault()),
                style = Antresol.typography.eyebrow,
                color = if (focused) AntresolColors.Label else AntresolColors.LabelSecondary,
            )
            Text(
                text = program?.title ?: stringResource(R.string.tv_nothing),
                style = Antresol.typography.pageTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null && program.subtitle.isNotBlank()) {
                Text(program.subtitle, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                LiveBadge(text = label, style = Antresol.typography.title, color = AntresolColors.Label, caps = false)
                if (program != null) {
                    Text(
                        stringResource(R.string.tv_time, programClock(context, program.start), programClock(context, program.end)),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                    )
                }
            }
            if (next != null) {
                Text(
                    stringResource(R.string.tv_next_at_long, programClock(context, next.start), Channels.programLine(next)),
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
private fun androidx.compose.foundation.layout.BoxScope.Progress(fraction: Float) {
    ProgressBar(
        fraction = fraction,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
