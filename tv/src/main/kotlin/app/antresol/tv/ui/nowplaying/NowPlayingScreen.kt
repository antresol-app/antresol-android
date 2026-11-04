package app.antresol.tv.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.playback.Chapters
import app.antresol.core.playback.lineIndexAt
import app.antresol.core.playback.plainText
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.playback.SleepTimer
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.RoundIconButton
import app.antresol.tv.ui.components.TrackRow
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.home.HeroBackground
import app.antresol.tv.ui.player.SeekBar
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** The side panel of the music screen. */
private enum class MusicPanel { Lyrics, UpNext }

/** Overlay menus of the screen. */
private enum class NowPlayingMenu { Speed, Sleep }

/**
 * Now Playing for music and audiobooks: artwork-tinted background, artwork, titles, a seek bar and
 * transport controls. Music shows lyrics (synced lines follow the song) or the queue on the right;
 * audiobooks show chapters, speed and the sleep timer. Playback continues when the user leaves.
 */
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel) {
    val audio = viewModel.audio
    val content = audio.content
    if (content == null) {
        MessageView(stringResource(TvR.string.now_playing_nothing))
        return
    }
    var menu by remember { mutableStateOf<NowPlayingMenu?>(null) }
    var panel by rememberSaveable { mutableStateOf(MusicPanel.Lyrics) }
    BackHandler(enabled = menu != null) { menu = null }

    val urls = LocalMediaUrls.current
    val track = audio.currentTrack
    val book = (content as? AudioContent.Audiobook)?.book
    val artwork = track?.cover ?: book?.card?.poster.orEmpty()
    val title = track?.title ?: book?.card?.title.orEmpty()
    val tintCard = Card(id = track?.id ?: book?.card?.id ?: 0, kind = if (book != null) Kind.AUDIOBOOK else Kind.ALBUM, title = title, poster = artwork)

    Box(Modifier.fillMaxSize()) {
        HeroBackground(tintCard)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 40.dp, top = 48.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Bottom) {
                    ArtworkImage(url = urls?.image(artwork), title = title, modifier = Modifier.size(196.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(TvR.string.now_playing).uppercase(), style = Antresol.typography.eyebrow)
                        Text(title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val byline = when (content) {
                            is AudioContent.Music -> listOfNotNull(track?.artist?.ifBlank { null }, track?.album?.ifBlank { null }).joinToString(" · ")
                            is AudioContent.Audiobook -> content.book.authors.joinToString(", ")
                        }
                        if (byline.isNotEmpty()) Text(byline, style = Antresol.typography.bodyEmphasis, color = AntresolColors.LabelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (content is AudioContent.Audiobook) ChapterLine(content, audio.positionMs)
                    }
                }
                Spacer(Modifier.weight(1f))
                audio.error?.let {
                    Text(stringResource(TvR.string.now_playing_error), style = Antresol.typography.caption, color = AntresolColors.Red)
                }
                viewModel.actionError?.let { error ->
                    Text(error.userMessage(LocalContext.current.resources), style = Antresol.typography.caption, color = AntresolColors.Red)
                }
                SeekBar(
                    positionMs = audio.positionMs,
                    bufferedMs = audio.positionMs,
                    durationMs = audio.durationMs,
                    onSeek = audio::skipBy,
                    onToggle = audio::togglePlayPause,
                    backStepMs = if (book != null) AudioEngine.BACK_STEP_MS else MUSIC_STEP_MS,
                    forwardStepMs = if (book != null) AudioEngine.FORWARD_STEP_MS else MUSIC_STEP_MS,
                )
                when (content) {
                    is AudioContent.Music -> MusicControls(viewModel, panel, onPanel = { panel = it }, onMenu = { menu = it })
                    is AudioContent.Audiobook -> AudiobookControls(audio, onMenu = { menu = it })
                }
            }
            Column(
                Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            ) {
                when (content) {
                    is AudioContent.Music -> if (panel == MusicPanel.Lyrics) LyricsPanel(viewModel) else UpNextPanel(audio)
                    is AudioContent.Audiobook -> ChaptersPanel(audio, content)
                }
            }
        }
        when (menu) {
            NowPlayingMenu.Speed -> OptionsPanel(
                title = stringResource(TvR.string.now_playing_speed),
                options = AudioEngine.SPEEDS.map { speed -> MenuOption(speedLabel(speed), speed == audio.speed) { audio.setPlaybackSpeed(speed) } },
                onDismiss = { menu = null },
            )
            NowPlayingMenu.Sleep -> OptionsPanel(
                title = stringResource(TvR.string.now_playing_sleep),
                options = SleepTimer.entries.map { option ->
                    MenuOption(sleepLabel(option, isBook = book != null), option == audio.sleepTimer) { audio.startSleepTimer(option) }
                },
                onDismiss = { menu = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun ChapterLine(content: AudioContent.Audiobook, positionMs: Long) {
    val chapters = content.chapters
    val index = Chapters.indexAt(chapters, positionMs)
    if (index < 0 || chapters.size < 2) return
    val chapter = chapters[index]
    val label = listOfNotNull(
        stringResource(TvR.string.now_playing_chapter_of, index + 1, chapters.size),
        chapter.title.ifBlank { null },
    ).joinToString(" · ")
    Text(label, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MusicControls(
    viewModel: NowPlayingViewModel,
    panel: MusicPanel,
    onPanel: (MusicPanel) -> Unit,
    onMenu: (NowPlayingMenu) -> Unit,
) {
    val audio = viewModel.audio
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.tryRequestFocus() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(TvIcons.Shuffle, stringResource(TvR.string.now_playing_shuffle), audio::toggleShuffle, active = audio.shuffle)
        RoundIconButton(TvIcons.Previous, stringResource(TvR.string.now_playing_previous), audio::previous)
        PlayPauseButton(audio, Modifier.focusRequester(play))
        RoundIconButton(TvIcons.Next, stringResource(TvR.string.now_playing_next), audio::next)
        RoundIconButton(
            icon = if (audio.repeatMode == Player.REPEAT_MODE_ONE) TvIcons.RepeatOne else TvIcons.Repeat,
            contentDescription = stringResource(if (audio.repeatMode == Player.REPEAT_MODE_ONE) TvR.string.now_playing_repeat_one else TvR.string.now_playing_repeat),
            onClick = audio::cycleRepeat,
            active = audio.repeatMode != Player.REPEAT_MODE_OFF,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(
            text = stringResource(if (panel == MusicPanel.Lyrics) TvR.string.now_playing_up_next else TvR.string.now_playing_lyrics),
            onClick = { onPanel(if (panel == MusicPanel.Lyrics) MusicPanel.UpNext else MusicPanel.Lyrics) },
            icon = if (panel == MusicPanel.Lyrics) TvIcons.Queue else TvIcons.Lyrics,
        )
        val track = audio.currentTrack
        if (track != null) {
            PillButton(
                text = stringResource(if (track.starred) TvR.string.music_in_favorites else TvR.string.music_favorite),
                onClick = viewModel::toggleStar,
                icon = if (track.starred) TvIcons.HeartFilled else TvIcons.Heart,
            )
        }
        SleepButton(audio, isBook = false, onMenu = onMenu)
    }
}

@Composable
private fun AudiobookControls(audio: AudioEngine, onMenu: (NowPlayingMenu) -> Unit) {
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.tryRequestFocus() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(TvIcons.Previous, stringResource(TvR.string.now_playing_previous), audio::previous)
        RoundIconButton(TvIcons.SkipBack, stringResource(TvR.string.now_playing_back_15), { audio.skipBy(-AudioEngine.BACK_STEP_MS) })
        PlayPauseButton(audio, Modifier.focusRequester(play))
        RoundIconButton(TvIcons.SkipForward, stringResource(TvR.string.now_playing_forward_30), { audio.skipBy(AudioEngine.FORWARD_STEP_MS) })
        RoundIconButton(TvIcons.Next, stringResource(TvR.string.now_playing_next), audio::next)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(
            text = stringResource(TvR.string.now_playing_speed) + " · " + speedLabel(audio.speed),
            onClick = { onMenu(NowPlayingMenu.Speed) },
            icon = TvIcons.Speed,
        )
        SleepButton(audio, isBook = true, onMenu = onMenu)
    }
}

@Composable
private fun PlayPauseButton(audio: AudioEngine, modifier: Modifier = Modifier) {
    RoundIconButton(
        icon = if (audio.playWhenReady) AntresolIcons.Pause else AntresolIcons.Play,
        contentDescription = stringResource(if (audio.playWhenReady) R.string.player_pause else R.string.action_play),
        onClick = { if (audio.error != null) audio.retry() else audio.togglePlayPause() },
        modifier = modifier,
        size = 68.dp,
        primary = true,
    )
}

@Composable
private fun SleepButton(audio: AudioEngine, isBook: Boolean, onMenu: (NowPlayingMenu) -> Unit) {
    val state = when (audio.sleepTimer) {
        SleepTimer.Off -> null
        SleepTimer.EndOfItem -> sleepLabel(SleepTimer.EndOfItem, isBook)
        else -> audio.sleepRemainingMs?.let { Durations.clock(it) }
    }
    PillButton(
        text = listOfNotNull(stringResource(TvR.string.now_playing_sleep), state).joinToString(" · "),
        onClick = { onMenu(NowPlayingMenu.Sleep) },
        icon = TvIcons.Moon,
    )
}

/** Lyrics of the current song; synced lines light up and scroll with the music. */
@Composable
private fun LyricsPanel(viewModel: NowPlayingViewModel) {
    PanelTitle(stringResource(TvR.string.now_playing_lyrics))
    when (val state = viewModel.lyrics) {
        LoadState.Loading -> Unit
        is LoadState.Failed -> PanelMessage(state.error.userMessage(LocalContext.current.resources))
        is LoadState.Ready -> {
            val lyrics = state.value
            if (lyrics == null || lyrics.plainText.isBlank()) {
                PanelMessage(stringResource(TvR.string.now_playing_no_lyrics))
                return
            }
            val lines = if (lyrics.synced && lyrics.lines.isNotEmpty()) lyrics.lines.map { it.text } else lyrics.plainText.lines()
            val active = lyrics.lineIndexAt(viewModel.audio.positionMs)
            val listState = rememberLazyListState()
            LaunchedEffect(active) {
                if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
            }
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines) { index, line ->
                    val current = index == active
                    Text(
                        text = line.ifBlank { "♪" },
                        style = Antresol.typography.bodyEmphasis.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold),
                        color = when {
                            !lyrics.synced -> AntresolColors.Label.copy(alpha = 0.85f)
                            current -> AntresolColors.Label
                            else -> AntresolColors.LabelTertiary
                        },
                    )
                }
            }
        }
    }
}

/** The next songs in play order; OK jumps to one. */
@Composable
private fun UpNextPanel(audio: AudioEngine) {
    PanelTitle(stringResource(TvR.string.now_playing_up_next))
    // Re-read when the current song or the shuffle order changes.
    val upNext = remember(audio.index, audio.shuffle, audio.content) { audio.upNext() }
    if (upNext.isEmpty()) {
        PanelMessage(stringResource(TvR.string.now_playing_queue_end))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(upNext, key = { _, (index, track) -> "$index/${track.id}" }) { position, (index, track) ->
            TrackRow(
                leading = (position + 1).toString(),
                title = track.title,
                subtitle = track.artist,
                trailing = Durations.clock(track.durationMs),
                onClick = { audio.playQueueItem(index) },
            )
        }
    }
}

/** Chapters of the audiobook; the current one is marked, OK jumps to one. */
@Composable
private fun ChaptersPanel(audio: AudioEngine, content: AudioContent.Audiobook) {
    val chapters = content.chapters
    PanelTitle(stringResource(R.string.detail_chapters))
    if (chapters.size < 2) {
        PanelMessage(Durations.clock(audio.positionMs) + " / " + Durations.clock(content.durationMs))
        return
    }
    val current = Chapters.indexAt(chapters, audio.positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) listState.animateScrollToItem(current - 2) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.idx }) { index, chapter ->
            TrackRow(
                leading = (index + 1).toString(),
                title = chapter.title.ifBlank { stringResource(TvR.string.now_playing_chapter_of, index + 1, chapters.size) },
                trailing = Durations.clock(chapter.startMs),
                playing = index == current,
                onClick = { audio.seekTo(chapter.startMs) },
            )
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = Antresol.typography.sectionTitle, modifier = Modifier.height(32.dp))
}

@Composable
private fun PanelMessage(text: String) {
    Text(text, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
}

private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

@Composable
private fun sleepLabel(option: SleepTimer, isBook: Boolean): String = when (option) {
    SleepTimer.Off -> stringResource(TvR.string.sleep_off)
    SleepTimer.EndOfItem -> stringResource(if (isBook) TvR.string.sleep_end_of_chapter else TvR.string.sleep_end_of_song)
    else -> stringResource(TvR.string.sleep_minutes, option.minutes)
}

/** Left/Right on the music seek bar move by this much. */
private const val MUSIC_STEP_MS = 10_000L
