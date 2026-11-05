package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.playback.Chapters
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.TrackRow
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.cardFocusKey
import app.antresol.tv.ui.components.kindLabel
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.ui.home.HeroBackground
import app.antresol.tv.ui.player.ResumeMode
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.format.Durations
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.format.remainingDuration
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion

/** Focus key of the first action (Play, Listen, Read) of a detail page. */
private const val PLAY_KEY = "action/play"

/** Focus key of the Want to Read button of a book page. */
private const val WANT_KEY = "action/want"
private const val WATCHLIST_KEY = "action/watchlist"
private const val WATCHED_KEY = "action/watched"

/**
 * The detail page of any card: tinted backdrop, artwork, title, meta and overview. Movies,
 * episodes and series add Play/Resume, watchlist and watched actions, track and cast lines, and
 * (series) season pills with episode cards; other kinds show related rows (albums, volumes).
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    audio: AudioEngine,
    onOpen: (Card) -> Unit,
    onPlay: (itemId: Long, mode: ResumeMode) -> Unit,
    onNowPlaying: () -> Unit,
    onRead: (volumeId: Long, fromStart: Boolean) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    // Back from the player: progress, watched marks and the next episode changed.
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) viewModel.refresh() else resumedOnce = true
    }
    when (val state = viewModel.state) {
        LoadState.Loading -> LoadingView()
        is LoadState.Failed -> ErrorView(state.error, onRetry = viewModel::reload, takeFocus = canTakeFocus())
        is LoadState.Ready -> DetailContent(
            model = state.value,
            viewModel = viewModel,
            audio = audio,
            actions = DetailActions(onOpen, onPlay, onNowPlaying, onRead),
            onScrolledToTop = onScrolledToTop,
            canTakeFocus = canTakeFocus,
        )
    }
}

/** Where the detail page's buttons and cards lead. */
private class DetailActions(
    val open: (Card) -> Unit,
    val play: (Long, ResumeMode) -> Unit,
    val nowPlaying: () -> Unit,
    val read: (Long, Boolean) -> Unit,
)

@Composable
private fun DetailContent(
    model: DetailModel,
    viewModel: DetailViewModel,
    audio: AudioEngine,
    actions: DetailActions,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    val video = model.video
    val music = model.music
    val book = model.book
    val chapters = book?.takeIf { it.isAudiobook }?.detail?.chapters.orEmpty()
    val tall = model.rows.isEmpty() && video?.seasons.isNullOrEmpty() && music?.tracks.isNullOrEmpty() && chapters.isEmpty()
    val headerHeight = (LocalConfiguration.current.screenHeightDp * if (tall) 1f else 0.62f).dp
    val firstKey = when {
        video?.playTarget != null -> PLAY_KEY
        !music?.tracks.isNullOrEmpty() -> PLAY_KEY
        book != null && (book.isAudiobook || book.comic != null) -> PLAY_KEY
        book != null && book.detail.kind != Kind.COMIC_VOLUME -> WANT_KEY
        // Nothing to play: the first action shown (a video always has Mark as Watched).
        video != null -> if (video.canWatchlist) WATCHLIST_KEY else WATCHED_KEY
        // An empty album or a comic volume without a readable file has no buttons: start on its rows.
        else -> model.rows.firstOrNull { it.cards.isNotEmpty() }?.let { cardFocusKey(it.id, it.cards.first()) }
    }
    val onOpen = actions.open
    val onPlay = actions.play
    LaunchedEffect(firstKey) {
        if (firstKey != null && canTakeFocus()) focus.restore(firstKey)
    }
    Box(Modifier.fillMaxSize()) {
        HeroBackground(model.card)
        VerticalShelfScrolling {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = headerHeight)
                            .padding(horizontal = ScreenPaddingH, vertical = 32.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        DetailHeader(model) {
                            if (video != null) {
                                VideoActions(
                                    video = video,
                                    kind = model.card.kind,
                                    focus = focus,
                                    playKey = PLAY_KEY,
                                    onPlay = onPlay,
                                    onToggleWatchlist = viewModel::toggleWatchlist,
                                    onToggleWatched = viewModel::toggleWatched,
                                )
                            }
                            if (music != null && music.tracks.isNotEmpty()) {
                                MusicActions(music, focus, viewModel, actions.nowPlaying)
                            }
                            if (book != null) {
                                BookActions(book, focus, viewModel, actions)
                            }
                            if (video != null || music != null || book != null) {
                                viewModel.actionError?.let { error ->
                                    Text(
                                        error.userMessage(LocalContext.current.resources),
                                        style = Antresol.typography.caption,
                                        color = AntresolColors.Red,
                                    )
                                }
                            }
                        }
                    }
                }
                if (music != null && music.tracks.isNotEmpty()) {
                    item(key = "tracks-title") { SectionTitle(stringResource(music.tracksTitle)) }
                    itemsIndexed(music.tracks, key = { index, track -> "track/${track.id}/$index" }) { index, track ->
                        TrackRow(
                            leading = (track.trackNo.takeIf { model.card.kind == Kind.ALBUM && it > 0 } ?: (index + 1)).toString(),
                            title = track.title,
                            subtitle = track.artist.takeIf { it.isNotBlank() && it != model.byline },
                            trailing = Durations.clock(track.durationMs),
                            playing = audio.currentTrack?.id == track.id,
                            onClick = { viewModel.playTracks(index, onStarted = actions.nowPlaying) },
                            modifier = Modifier
                                .padding(horizontal = ScreenPaddingH)
                                .remembersFocus(focus, "track/${track.id}/$index"),
                        )
                    }
                }
                if (book != null && chapters.size > 1) {
                    val playingBook = (audio.content as? AudioContent.Audiobook)?.takeIf { it.book.card.id == book.detail.card.id }
                    val current = playingBook?.let { Chapters.indexAt(it.chapters, audio.positionMs) }
                    item(key = "chapters-title") { SectionTitle(stringResource(R.string.detail_chapters)) }
                    itemsIndexed(chapters, key = { _, chapter -> "chapter/${chapter.idx}" }) { index, chapter ->
                        TrackRow(
                            leading = (index + 1).toString(),
                            title = chapter.title.ifBlank { stringResource(TvR.string.now_playing_chapter_of, index + 1, chapters.size) },
                            subtitle = Durations.clock(chapter.startMs),
                            trailing = Durations.clock((chapter.endMs - chapter.startMs).coerceAtLeast(0)),
                            playing = current == index,
                            onClick = { viewModel.listen(fromMs = chapter.startMs, onStarted = actions.nowPlaying) },
                            modifier = Modifier
                                .padding(horizontal = ScreenPaddingH)
                                .remembersFocus(focus, "chapter/${chapter.idx}"),
                        )
                    }
                }
                if (video != null && video.seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        SeasonsSection(
                            seasons = video.seasons,
                            initialEpisodeId = video.playTarget?.itemId,
                            focus = focus,
                            onPlayEpisode = { onPlay(it.id, ResumeMode.Ask) },
                            onOpenEpisode = { onOpen(it.toCard()) },
                        )
                    }
                }
                items(model.rows, key = { it.id }) { row ->
                    ShelfRow(
                        shelfId = row.id,
                        title = row.titleRes?.let { stringResource(it) } ?: row.title,
                        cards = row.cards,
                        onOpen = onOpen,
                        focus = focus,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(model: DetailModel, actions: @Composable () -> Unit) {
    val card = model.card
    val urls = LocalMediaUrls.current
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
        if (card.backdrop.isEmpty()) {
            val square = card.kind in setOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST)
            ArtworkImage(
                url = urls?.image(card.poster),
                title = card.title,
                modifier = Modifier
                    .width(if (square) 190.dp else 160.dp)
                    .aspectRatio(if (square) 1f else AntresolMotion.POSTER_ASPECT),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 620.dp)) {
                DetailText(model)
            }
            actions()
        }
    }
}

@Composable
private fun DetailText(model: DetailModel) {
    val typography = Antresol.typography
    val card = model.card
    kindLabel(card.kind)?.let { Text(it.uppercase(), style = typography.eyebrow) }
    Text(card.title, style = typography.heroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val byline = listOfNotNull(
        model.byline.ifBlank { null } ?: card.subtitle.ifBlank { null },
        model.narrator.ifBlank { null }?.let { stringResource(R.string.detail_narrated_by, it) },
    ).joinToString(" · ")
    if (byline.isNotEmpty()) Text(byline, style = typography.bodyEmphasis, color = AntresolColors.LabelSecondary)
    val meta = listOfNotNull(
        card.year?.toString(),
        model.durationMs?.let { spokenDuration(it) },
        model.songCount?.let { pluralStringResource(R.plurals.song_count, it, it) },
        model.rating?.ifBlank { null },
        model.genres.take(3).joinToString(", ").ifBlank { null },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (meta.isNotEmpty()) Text(meta, style = typography.body, color = AntresolColors.LabelSecondary)
        BadgeRow(card.badges, max = 4)
    }
    if (model.overview.isNotBlank()) {
        Text(
            text = model.overview,
            style = typography.body,
            color = AntresolColors.Label.copy(alpha = 0.85f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    model.video?.let { VideoFacts(it) }
}

/** "Audio: English, Russian · Subtitles: English" and the first few cast members. */
@Composable
private fun VideoFacts(video: VideoInfo) {
    val resources = LocalContext.current.resources
    val tracks = listOfNotNull(
        video.audio.takeIf { it.size > 1 }?.let { list ->
            stringResource(R.string.detail_audio) + ": " + list.joinToString(", ") { TrackLabels.language(it.lang) ?: TrackLabels.audio(resources, it) }
        },
        video.subtitles.takeIf { it.isNotEmpty() }?.let { list ->
            stringResource(R.string.detail_subtitles) + ": " + list.map { TrackLabels.subtitle(resources, it) }.distinct().joinToString(", ")
        },
    ).joinToString(" · ")
    if (tracks.isNotEmpty()) {
        Text(tracks, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (video.cast.isNotEmpty()) {
        Text(
            stringResource(R.string.detail_cast) + ": " + video.cast.take(5).joinToString(", ") { it.name },
            style = Antresol.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VideoActions(
    video: VideoInfo,
    kind: String,
    focus: FocusMemory,
    playKey: String,
    onPlay: (Long, ResumeMode) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val target = video.playTarget
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .trapHorizontalFocus(),
    ) {
        if (target != null) {
            val resuming = target.resumeMs > 0
            val label = when {
                target.season != null && target.episode != null -> stringResource(
                    if (resuming) R.string.detail_resume_episode else R.string.detail_play_episode,
                    target.season,
                    target.episode,
                )
                resuming -> stringResource(R.string.action_resume)
                else -> stringResource(R.string.action_play)
            }
            PillButton(
                text = label,
                onClick = { onPlay(target.itemId, ResumeMode.Resume) },
                icon = AntresolIcons.Play,
                style = PillStyle.Primary,
                modifier = Modifier.remembersFocus(focus, playKey),
            )
            if (resuming && kind != Kind.SERIES && kind != Kind.SEASON) {
                PillButton(
                    text = stringResource(R.string.action_play_from_start),
                    onClick = { onPlay(target.itemId, ResumeMode.FromStart) },
                    icon = AntresolIcons.Restart,
                    modifier = Modifier.remembersFocus(focus, "action/restart"),
                )
            }
        }
        if (video.canWatchlist) {
            PillButton(
                text = stringResource(if (video.inWatchlist) R.string.detail_in_watchlist else R.string.action_add_watchlist),
                onClick = onToggleWatchlist,
                icon = if (video.inWatchlist) AntresolIcons.Check else AntresolIcons.Plus,
                modifier = Modifier.remembersFocus(focus, WATCHLIST_KEY),
            )
        }
        PillButton(
            text = stringResource(if (video.watched) R.string.action_mark_unwatched else R.string.action_mark_watched),
            onClick = onToggleWatched,
            icon = AntresolIcons.CheckCircle,
            modifier = Modifier.remembersFocus(focus, WATCHED_KEY),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 4.dp, bottom = 4.dp))
}

/** Play, Shuffle and Favorite of an album, playlist or artist. */
@Composable
private fun MusicActions(music: MusicInfo, focus: FocusMemory, viewModel: DetailViewModel, onNowPlaying: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .trapHorizontalFocus(),
    ) {
        PillButton(
            text = stringResource(R.string.action_play),
            onClick = { viewModel.playTracks(0, onStarted = onNowPlaying) },
            icon = AntresolIcons.Play,
            style = PillStyle.Primary,
            modifier = Modifier.remembersFocus(focus, PLAY_KEY),
        )
        PillButton(
            text = stringResource(R.string.action_shuffle),
            onClick = { viewModel.playTracks(0, shuffled = true, onStarted = onNowPlaying) },
            icon = TvIcons.Shuffle,
            modifier = Modifier.remembersFocus(focus, "action/shuffle"),
        )
        if (music.canStar) {
            PillButton(
                text = stringResource(if (music.starred) TvR.string.music_in_favorites else TvR.string.music_favorite),
                onClick = viewModel::toggleStar,
                icon = if (music.starred) TvIcons.HeartFilled else TvIcons.Heart,
                modifier = Modifier.remembersFocus(focus, "action/star"),
            )
        }
    }
}

/** Listen / Read / Want to Read of a book page; e-books explain that they are read elsewhere. */
@Composable
private fun BookActions(book: BookInfo, focus: FocusMemory, viewModel: DetailViewModel, actions: DetailActions) {
    val detail = book.detail
    if (book.isAudiobook && book.resumeMs > 0) {
        Text(
            remainingDuration((detail.durationMs - book.resumeMs).coerceAtLeast(0)),
            style = Antresol.typography.caption,
        )
    }
    if (detail.kind == Kind.COMIC_VOLUME && book.comic == null) {
        Text(stringResource(TvR.string.comic_no_pages), style = Antresol.typography.caption)
    }
    if (book.isEbook) {
        Text(
            stringResource(TvR.string.ebook_unavailable),
            style = Antresol.typography.caption,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .trapHorizontalFocus(),
    ) {
        if (book.isAudiobook) {
            PillButton(
                text = stringResource(if (book.resumeMs > 0) R.string.action_resume else R.string.action_listen),
                onClick = { viewModel.listen(onStarted = actions.nowPlaying) },
                icon = AntresolIcons.Play,
                style = PillStyle.Primary,
                modifier = Modifier.remembersFocus(focus, PLAY_KEY),
            )
            if (book.resumeMs > 0) {
                PillButton(
                    text = stringResource(TvR.string.audiobook_listen_from_start),
                    onClick = { viewModel.listen(fromStart = true, onStarted = actions.nowPlaying) },
                    icon = AntresolIcons.Restart,
                    modifier = Modifier.remembersFocus(focus, "action/restart"),
                )
            }
        }
        book.comic?.let { target ->
            PillButton(
                text = target.continuePage?.let { stringResource(TvR.string.comic_continue, it) } ?: stringResource(R.string.action_read),
                onClick = { actions.read(target.volumeId, false) },
                icon = TvIcons.Book,
                style = PillStyle.Primary,
                modifier = Modifier.remembersFocus(focus, PLAY_KEY),
            )
            if (target.continuePage != null) {
                PillButton(
                    text = stringResource(TvR.string.comic_read_from_start),
                    onClick = { actions.read(target.volumeId, true) },
                    icon = AntresolIcons.Restart,
                    modifier = Modifier.remembersFocus(focus, "action/read-start"),
                )
            }
        }
        if (detail.kind != Kind.COMIC_VOLUME) {
            PillButton(
                text = stringResource(if (book.want) TvR.string.book_in_want_to_read else TvR.string.book_want_to_read),
                onClick = viewModel::toggleWant,
                icon = if (book.want) AntresolIcons.Check else AntresolIcons.Plus,
                modifier = Modifier.remembersFocus(focus, WANT_KEY),
            )
        }
    }
}
