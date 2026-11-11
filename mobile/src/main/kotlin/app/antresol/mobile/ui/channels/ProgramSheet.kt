package app.antresol.mobile.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.channels.Channels
import app.antresol.core.model.ChannelProgram
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.PrimaryPillButton
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

/** A programme picked in the guide, with its channel. */
data class GuidePick(val channelId: Long, val number: Int, val name: String, val favorite: Boolean, val program: ChannelProgram)

/**
 * A programme's details (the guide's tap): the still, title and episode, «N · channel», the time with
 * its duration (live progress while on air), genres, year and rating, the overview, «Смотреть» while
 * it is on air, and the channel's favourite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramSheet(pick: GuidePick, nowMs: Long, onWatch: () -> Unit, onToggleFavorite: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current
    val program = pick.program
    val onAir = Channels.isOnAir(program, nowMs)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProgrammeStill(
                url = urls?.image(programmeArt(program)),
                shape = AntresolShapes.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
            )
            Text(
                stringResource(R.string.tv_channel_number, pick.number, pick.name),
                style = Antresol.typography.caption,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(program.title, style = Antresol.typography.pageTitle)
            if (program.subtitle.isNotBlank()) {
                Text(program.subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary))
            }
            ProgramTimeLine(program, nowMs)
            val facts = listOfNotNull(
                program.genres.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.replaceFirstChar(Char::titlecase) },
                program.year?.toString(),
                program.rating.ifBlank { null },
            ).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, style = Antresol.typography.caption)
            if (program.overview.isNotBlank()) {
                Text(program.overview, style = Antresol.typography.body, modifier = Modifier.padding(top = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                if (onAir) {
                    PrimaryPillButton(
                        stringResource(R.string.tv_watch_now),
                        onClick = onWatch,
                        icon = AntresolIcons.Play,
                        modifier = Modifier.weight(1f).testTag("guide-watch"),
                    )
                }
                GlassPillButton(
                    stringResource(if (pick.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                    onClick = onToggleFavorite,
                    icon = if (pick.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** «21:10–22:40 · 90 мин», with the live progress («В эфире до 22:40») while it is on air. */
@Composable
fun ProgramTimeLine(program: ChannelProgram, nowMs: Long) {
    val context = LocalContext.current
    val start = Channels.epochMs(program.start)
    val end = Channels.epochMs(program.end)
    val minutes = if (start != null && end != null) ((end - start) / 60_000L).toInt() else null
    val time = listOfNotNull(
        stringResource(R.string.tv_time, programClock(context, program.start), programClock(context, program.end)),
        minutes?.let { stringResource(R.string.tv_minutes, it) },
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(time, style = Antresol.typography.bodyEmphasis)
        if (Channels.isOnAir(program, nowMs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressBar(Channels.progress(program, nowMs), Modifier.weight(1f))
                Text(stringResource(R.string.tv_on_air_until, programClock(context, program.end)), style = Antresol.typography.caption)
            }
        }
    }
}
