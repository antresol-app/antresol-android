package app.antresol.mobile.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Lyrics
import app.antresol.mobile.R
import app.antresol.mobile.playback.NowPlaying
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.playback.PlaybackService
import app.antresol.mobile.playback.SleepTimer
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.mobile.ui.components.SkipSecondsButton
import app.antresol.mobile.ui.components.TimeSlider
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale
import app.antresol.ui.R as UiR

/** What the lower half of Now Playing shows instead of the large artwork. */
private enum class Panel { Artwork, Lyrics, Queue, Chapters }

/**
 * The full Now Playing screen over the app: artwork on its own tint, title, star, scrubber and
 * transport. Music adds shuffle/repeat, lyrics (synced lines follow the song and seek on tap)
 * and the queue; audiobooks add 15/30 s skips, chapters, speed and the whole-book scrubber.
 * Both have the sleep timer.
 */
@Composable
fun NowPlayingScreen(playback: PlaybackConnection, client: AntresolClient, onClose: () -> Unit) {
    val playing = playback.current
    LaunchedEffect(playing == null) { if (playing == null) onClose() }
    playing ?: return
    BackHandler(onBack = onClose)
    var panel by rememberSaveable { mutableStateOf(Panel.Artwork) }
    var sheet by remember { mutableStateOf<NowPlayingSheet?>(null) }
    val tint by rememberArtworkTint(playing.artworkUrl, AntresolColors.BrandStart)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AntresolColors.Background)
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.85f), 0.65f to tint.copy(alpha = 0.2f), 1f to AntresolColors.Background))
            .clickable(enabled = false) {}
            .testTag("now-playing"),
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
            TopRow(playback, playing, onClose)
            if (wide) {
                Row(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        PanelContent(panel, playback, client, playing)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        Details(playback, playing, panel, onPanel = { panel = if (panel == it) Panel.Artwork else it }, onSheet = { sheet = it })
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    PanelContent(panel, playback, client, playing)
                }
                Details(playback, playing, panel, onPanel = { panel = if (panel == it) Panel.Artwork else it }, onSheet = { sheet = it })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    sheet?.let { open -> OptionsSheet(open, playback, onDismiss = { sheet = null }) }
}

@Composable
private fun TopRow(playback: PlaybackConnection, playing: NowPlaying, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(MobileIcons.ChevronDown, stringResource(R.string.now_playing_close), onClose, modifier = Modifier.testTag("now-playing-close"))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (playing.isAudiobook) R.string.now_playing_listening else R.string.now_playing_title).uppercase(Locale.getDefault()),
                style = Antresol.typography.eyebrow,
            )
            val source = if (playing.isAudiobook) playback.book?.authors?.joinToString(", ").orEmpty() else playing.album
            if (source.isNotBlank()) Text(source, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing.trackId != null) {
            RoundIconButton(
                icon = if (playing.starred) MobileIcons.StarFilled else MobileIcons.Star,
                contentDescription = stringResource(if (playing.starred) R.string.now_playing_unstar else R.string.now_playing_star),
                onClick = playback::toggleStar,
                glass = true,
                size = 40.dp,
                iconSize = 22.dp,
                tint = if (playing.starred) AntresolColors.Orange else AntresolColors.Label,
                modifier = Modifier.testTag("now-playing-star"),
            )
        } else {
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun PanelContent(panel: Panel, playback: PlaybackConnection, client: AntresolClient, playing: NowPlaying) {
    when (panel) {
        Panel.Artwork -> ArtworkImage(
            url = playing.artworkUrl,
            title = playing.title,
            shape = AntresolShapes.sheet,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .aspectRatio(if (playing.isAudiobook) 2f / 3f else 1f, matchHeightConstraintsFirst = true),
        )
        Panel.Lyrics -> playing.trackId?.let { LyricsPanel(it, playback, client) }
        Panel.Queue -> QueuePanel(playback)
        Panel.Chapters -> ChaptersPanel(playback)
    }
}

@Composable
private fun ColumnScope.Details(
    playback: PlaybackConnection,
    playing: NowPlaying,
    panel: Panel,
    onPanel: (Panel) -> Unit,
    onSheet: (NowPlayingSheet) -> Unit,
) {
    Text(playing.title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("now-playing-title"))
    val subtitle = if (playing.isAudiobook) playback.currentChapter?.title ?: playing.artist else playing.artist
    if (subtitle.isNotBlank()) {
        Text(subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(12.dp))
    if (playing.isAudiobook) {
        TimeSlider(playback.bookPositionMs, playback.bookDurationMs, onSeek = playback::seekBook)
    } else {
        TimeSlider(playback.positionMs, playback.durationMs, onSeek = playback::seekTo)
    }
    playback.error?.let {
        Text(stringResource(R.string.now_playing_error), style = Antresol.typography.caption.copy(color = AntresolColors.Red))
    }
    Spacer(Modifier.height(8.dp))
    Transport(playback, playing)
    Spacer(Modifier.height(16.dp))
    // Panels and options.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (playing.isAudiobook) {
            if (!playback.book?.chapters.isNullOrEmpty()) {
                PanelToggle(MobileIcons.Queue, stringResource(UiR.string.detail_chapters), panel == Panel.Chapters, "panel-chapters") { onPanel(Panel.Chapters) }
            }
            TextToggle(speedLabel(playback.speed), stringResource(R.string.now_playing_speed), "now-playing-speed") { onSheet(NowPlayingSheet.Speed) }
        } else {
            if (playing.hasLyrics) {
                PanelToggle(MobileIcons.Lyrics, stringResource(R.string.now_playing_lyrics), panel == Panel.Lyrics, "panel-lyrics") { onPanel(Panel.Lyrics) }
            }
            PanelToggle(MobileIcons.Queue, stringResource(R.string.now_playing_queue), panel == Panel.Queue, "panel-queue") { onPanel(Panel.Queue) }
        }
        SleepToggle(playback) { onSheet(NowPlayingSheet.Sleep) }
    }
}

@Composable
private fun Transport(playback: PlaybackConnection, playing: NowPlaying) {
    val paused = !playback.playWhenReady
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (playing.isAudiobook) {
            val back = (PlaybackService.SEEK_BACK_MS / 1000).toInt()
            val forward = (PlaybackService.SEEK_FORWARD_MS / 1000).toInt()
            RoundIconButton(MobileIcons.SkipPrevious, stringResource(R.string.now_playing_previous_chapter), onClick = { previousChapter(playback) }, iconSize = 24.dp)
            SkipSecondsButton(back, forward = false, stringResource(R.string.player_skip_back, back), { playback.skipBy(-PlaybackService.SEEK_BACK_MS) }, size = 56.dp)
            PlayPause(paused, playback)
            SkipSecondsButton(forward, forward = true, stringResource(R.string.player_skip_forward, forward), { playback.skipBy(PlaybackService.SEEK_FORWARD_MS) }, size = 56.dp)
            RoundIconButton(MobileIcons.SkipNext, stringResource(R.string.now_playing_next_chapter), onClick = { nextChapter(playback) }, iconSize = 24.dp)
        } else {
            RoundIconButton(
                MobileIcons.Shuffle,
                stringResource(UiR.string.action_shuffle),
                onClick = playback::toggleShuffle,
                tint = if (playback.shuffle) AntresolColors.Accent else AntresolColors.LabelSecondary,
                iconSize = 22.dp,
                modifier = Modifier.testTag("now-playing-shuffle"),
            )
            RoundIconButton(MobileIcons.SkipPrevious, stringResource(R.string.now_playing_previous), onClick = playback::previous, size = 56.dp, iconSize = 30.dp)
            PlayPause(paused, playback)
            RoundIconButton(MobileIcons.SkipNext, stringResource(R.string.now_playing_next), onClick = playback::next, size = 56.dp, iconSize = 30.dp)
            RoundIconButton(
                if (playback.repeatMode == Player.REPEAT_MODE_ONE) MobileIcons.RepeatOne else MobileIcons.Repeat,
                stringResource(R.string.now_playing_repeat),
                onClick = playback::cycleRepeat,
                tint = if (playback.repeatMode != Player.REPEAT_MODE_OFF) AntresolColors.Accent else AntresolColors.LabelSecondary,
                iconSize = 22.dp,
                modifier = Modifier.testTag("now-playing-repeat"),
            )
        }
    }
}

@Composable
private fun PlayPause(paused: Boolean, playback: PlaybackConnection) {
    RoundIconButton(
        icon = if (paused) AntresolIcons.Play else AntresolIcons.Pause,
        contentDescription = stringResource(if (paused) UiR.string.action_play else UiR.string.player_pause),
        onClick = playback::togglePlayPause,
        size = 72.dp,
        iconSize = 34.dp,
        filled = true,
        modifier = Modifier.testTag("now-playing-play-pause"),
    )
}

private fun previousChapter(playback: PlaybackConnection) {
    val chapters = playback.book?.chapters.orEmpty()
    val current = playback.currentChapter
    if (chapters.isEmpty() || current == null) return playback.skipBy(-PlaybackService.SEEK_BACK_MS)
    // Like a CD player: restart the chapter unless it only just began.
    val target = if (playback.bookPositionMs - current.startMs > RESTART_THRESHOLD_MS) current else chapters.getOrNull(chapters.indexOf(current) - 1) ?: current
    playback.seekBook(target.startMs)
}

private fun nextChapter(playback: PlaybackConnection) {
    val chapters = playback.book?.chapters.orEmpty()
    val current = playback.currentChapter ?: return playback.skipBy(PlaybackService.SEEK_FORWARD_MS)
    chapters.getOrNull(chapters.indexOf(current) + 1)?.let { playback.seekBook(it.startMs) }
}

private const val RESTART_THRESHOLD_MS = 3_000L

@Composable
private fun PanelToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(if (selected) AntresolColors.Label else androidx.compose.ui.graphics.Color.Transparent, AntresolShapes.pill)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) AntresolColors.LabelInverse else AntresolColors.LabelSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TextToggle(text: String, description: String, tag: String, onClick: () -> Unit) {
    Text(
        text,
        style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.LabelSecondary),
        modifier = Modifier
            .background(AntresolColors.Glass.copy(alpha = 0.5f), AntresolShapes.pill)
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag),
    )
}

@Composable
private fun SleepToggle(playback: PlaybackConnection, onClick: () -> Unit) {
    val timer = playback.sleepTimer
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer is SleepTimer.At) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(
        modifier = Modifier
            .background(if (timer != null) AntresolColors.Label else AntresolColors.Glass.copy(alpha = 0.5f), AntresolShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("now-playing-sleep"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (timer != null) AntresolColors.LabelInverse else AntresolColors.LabelSecondary
        Icon(MobileIcons.Moon, contentDescription = stringResource(R.string.sleep_timer), tint = color, modifier = Modifier.size(20.dp))
        val label = when (timer) {
            is SleepTimer.At -> Durations.clock((timer.endsAtMs - now).coerceAtLeast(0))
            SleepTimer.EndOfItem -> stringResource(R.string.sleep_end_short)
            null -> null
        }
        if (label != null) Text(label, style = Antresol.typography.bodyEmphasis.copy(color = color))
    }
}

private fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else String.format(Locale.ROOT, "%.2f", speed).trimEnd('0')
    return "$text×"
}

// region Panels

@Composable
private fun LyricsPanel(trackId: Long, playback: PlaybackConnection, client: AntresolClient) {
    val lyrics by produceState<Result<Lyrics?>?>(null, trackId) {
        value = try {
            Result.success(client.lyrics(trackId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val result = lyrics ?: return LoadingView()
    val loaded = result.getOrNull()
    if (loaded == null || (loaded.lines.isEmpty() && loaded.lyrics.isBlank())) {
        return EmptyView(stringResource(R.string.now_playing_no_lyrics))
    }
    if (!loaded.synced || loaded.lines.isEmpty()) {
        Text(
            loaded.lyrics,
            style = Antresol.typography.title.copy(color = AntresolColors.Label, lineHeight = Antresol.typography.title.fontSize * 1.5f),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("lyrics"),
        )
        return
    }
    val lines = loaded.lines
    val currentLine = lines.indexOfLast { it.t * 1000 <= playback.positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine) { listState.animateScrollToItem((currentLine - 2).coerceAtLeast(0)) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("lyrics"), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lines) { index, line ->
            val active = index == currentLine
            Text(
                line.text.ifBlank { "♪" },
                style = Antresol.typography.sectionTitle.copy(
                    color = if (active) AntresolColors.Label else AntresolColors.LabelTertiary,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { playback.seekTo((line.t * 1000).toLong()) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QueuePanel(playback: PlaybackConnection) {
    val queue = playback.queue
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = playback.currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.now_playing_queue), style = Antresol.typography.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("queue")) {
            itemsIndexed(queue, key = { index, item -> "$index-${item.mediaId}" }) { index, item ->
                QueueRow(item, index, current = index == playback.currentIndex, onClick = { playback.jumpTo(index) })
            }
        }
    }
}

@Composable
private fun QueueRow(item: MediaItem, index: Int, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkImage(
            url = item.mediaMetadata.artworkUri?.toString(),
            title = item.mediaMetadata.title?.toString().orEmpty(),
            shape = AntresolShapes.badge,
            modifier = Modifier.size(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.mediaMetadata.title?.toString().orEmpty(),
                style = Antresol.typography.body.copy(color = if (current) AntresolColors.Accent else AntresolColors.Label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.mediaMetadata.artist?.toString().orEmpty(), style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text((index + 1).toString(), style = Antresol.typography.caption, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
    }
}

@Composable
private fun ChaptersPanel(playback: PlaybackConnection) {
    val chapters = playback.book?.chapters.orEmpty()
    val current = playback.currentChapter
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOf(current).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(UiR.string.detail_chapters), style = Antresol.typography.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("chapters")) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.idx }) { _, chapter ->
                val active = chapter == current
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { playback.playChapter(chapter) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapter.title,
                        style = Antresol.typography.body.copy(color = if (active) AntresolColors.Accent else AntresolColors.Label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(Durations.clock(chapter.startMs), style = Antresol.typography.caption)
                }
            }
        }
    }
}

// endregion

// region Sheets

private enum class NowPlayingSheet { Speed, Sleep }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(sheet: NowPlayingSheet, playback: PlaybackConnection, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            when (sheet) {
                NowPlayingSheet.Speed -> {
                    SheetTitle(stringResource(R.string.now_playing_speed))
                    PlaybackConnection.SPEEDS.forEach { speed ->
                        SheetOption(speedLabel(speed), selected = speed == playback.speed) {
                            playback.changeSpeed(speed)
                            onDismiss()
                        }
                    }
                }
                NowPlayingSheet.Sleep -> {
                    SheetTitle(stringResource(R.string.sleep_timer))
                    SheetOption(stringResource(R.string.sleep_off), selected = playback.sleepTimer == null) {
                        playback.cancelSleepTimer()
                        onDismiss()
                    }
                    val timer = playback.sleepTimer
                    PlaybackConnection.SLEEP_MINUTES.forEach { minutes ->
                        SheetOption(stringResource(R.string.sleep_minutes, minutes), selected = (timer as? SleepTimer.At)?.minutes == minutes) {
                            playback.setSleepTimer(minutes)
                            onDismiss()
                        }
                    }
                    val endLabel = if (playback.current?.isAudiobook == true && !playback.book?.chapters.isNullOrEmpty()) {
                        R.string.sleep_end_of_chapter
                    } else {
                        R.string.sleep_end_of_track
                    }
                    SheetOption(stringResource(endLabel), selected = timer == SleepTimer.EndOfItem) {
                        playback.setSleepTimer(null)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun SheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(22.dp)) {
            if (selected) Icon(AntresolIcons.Check, contentDescription = null, tint = AntresolColors.Accent)
        }
        Text(label, style = Antresol.typography.body)
    }
}

// endregion
