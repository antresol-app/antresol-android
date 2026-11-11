package app.antresol.mobile.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.focusClickable
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

/**
 * «Continue»: the channel this profile watched last, on top of the TV page. The programme on air
 * as a large picture (logo, live progress), then the eyebrow, its title, the channel with its time
 * and what is next. On wide screens the text sits beside the picture. A tap resumes it live.
 */
@Composable
fun ContinueChannel(channel: Channel, nowMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.tv_continue_watching, channel.name)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .focusClickable(AntresolShapes.card, role = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Picture(channel, nowMs, Modifier.weight(1f))
                Details(channel, nowMs, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Picture(channel, nowMs, Modifier.fillMaxWidth())
                Details(channel, nowMs, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Picture(channel: Channel, nowMs: Long, modifier: Modifier) {
    val urls = LocalMediaUrls.current
    val program = Channels.showing(channel, nowMs)
    val art = program?.let { urls?.image(it.backdrop.ifEmpty { it.poster }) }
    val frame = modifier.aspectRatio(AntresolMotion.BACKDROP_ASPECT)
    if (art != null) {
        ArtworkImage(url = art, title = program.title, shape = AntresolShapes.card, modifier = frame) {
            ChannelLogoBadge(
                name = channel.name,
                logo = channel.logo,
                width = 72.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .shadow(6.dp, AntresolShapes.smallCard),
            )
            Progress(Channels.progress(program, nowMs))
        }
    } else {
        Box(frame) {
            ChannelLogo(name = channel.name, logo = channel.logo, shape = AntresolShapes.card, filled = true, modifier = Modifier.fillMaxSize())
            if (program != null) Progress(Channels.progress(program, nowMs))
        }
    }
}

@Composable
private fun Details(channel: Channel, nowMs: Long, modifier: Modifier) {
    val context = LocalContext.current
    val program = Channels.showing(channel, nowMs)
    val next = channel.next?.takeIf { it != program }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.tv_continue).uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow)
        Text(
            text = program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing),
            style = Antresol.typography.sectionTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            LiveBadge(
                text = stringResource(R.string.tv_channel_number, channel.number, channel.name),
                style = Antresol.typography.title,
                caps = false,
                modifier = Modifier.weight(1f, fill = false),
            )
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
                style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoxScope.Progress(fraction: Float) {
    ProgressBar(
        fraction = fraction,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp),
    )
}
