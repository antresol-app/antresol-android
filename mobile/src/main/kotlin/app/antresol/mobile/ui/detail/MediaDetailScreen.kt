package app.antresol.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.Chapter
import app.antresol.core.model.Kind
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.TrackRow
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.components.kindLabel
import app.antresol.mobile.ui.music.PlayShuffleRow
import app.antresol.mobile.ui.playlists.ConfirmSheet
import app.antresol.mobile.ui.playlists.PlaylistNameSheet
import app.antresol.mobile.ui.playlists.PlaylistPickerHost
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import java.util.Locale

/**
 * Album, artist, playlist, book, audiobook and comic detail: centred artwork on its own tint,
 * title, byline and facts, the Want to Read toggle for books, description, then songs,
 * chapters, volumes or albums.
 */
@Composable
fun MediaDetailScreen(
    viewModel: MediaDetailViewModel,
    playback: PlaybackConnection,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
    onRead: (BookAction) -> Unit,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    NoticeSnackbar(viewModel.notice, snackbar, viewModel::consumeNotice)
    PlaylistPickerHost(viewModel.playlistPicker, snackbar)
    var dialog by rememberSaveable { mutableStateOf<PlaylistDialog?>(null) }
    Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        when (val state = viewModel.state) {
            LoadState.Loading -> DetailStateBox(onBack) { LoadingView() }
            is LoadState.Failed -> DetailStateBox(onBack) { ErrorView(state.error, onRetry = viewModel::reload) }
            is LoadState.Ready -> MediaDetailContent(state.value, viewModel, playback, onOpen, onBack, onRead, onPlaylistDialog = { dialog = it })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(LocalBottomBarPadding.current))
    }
    val title = (viewModel.state as? LoadState.Ready)?.value?.card?.title.orEmpty()
    when (dialog) {
        PlaylistDialog.Rename -> PlaylistNameSheet(
            title = stringResource(R.string.playlist_rename),
            confirm = stringResource(R.string.playlist_rename),
            initial = title,
            onConfirm = { name ->
                dialog = null
                viewModel.renamePlaylist(name)
            },
            onDismiss = { dialog = null },
        )
        PlaylistDialog.Delete -> ConfirmSheet(
            title = stringResource(UiR.string.playlist_delete_title, title),
            message = stringResource(UiR.string.playlist_delete_body),
            confirm = stringResource(UiR.string.playlist_delete),
            confirmTag = "playlist-delete-confirm",
            onConfirm = {
                dialog = null
                viewModel.deletePlaylist(onDeleted = onBack)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** The playlist dialogs a detail page can show. */
private enum class PlaylistDialog { Rename, Delete }

@Composable
private fun MediaDetailContent(
    detail: MediaDetail,
    viewModel: MediaDetailViewModel,
    playback: PlaybackConnection,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
    onRead: (BookAction) -> Unit,
    onPlaylistDialog: (PlaylistDialog) -> Unit,
) {
    val urls = LocalMediaUrls.current
    val card = detail.card
    val bottom = LocalBottomBarPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 24.dp),
    ) {
        item("header") {
            DetailHeader(tintUrl = urls?.image(card.poster), onBack = onBack, artwork = {}) {
                val (aspect, shape) = when (card.kind) {
                    Kind.ALBUM, Kind.PLAYLIST -> 1f to AntresolShapes.card
                    Kind.ARTIST -> 1f to CircleShape
                    else -> AntresolMotion.POSTER_ASPECT to AntresolShapes.card
                }
                ArtworkImage(
                    url = urls?.image(card.poster),
                    title = card.title,
                    shape = shape,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 240.dp)
                        .fillMaxWidth(if (aspect < 1f) 0.5f else 0.62f)
                        .aspectRatio(aspect),
                )
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    kindLabel(card.kind)?.let { Text(it.uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow) }
                    Text(
                        card.title,
                        style = Antresol.typography.pageTitle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp).testTag("detail-title"),
                    )
                    if (detail.byline.isNotBlank()) {
                        Text(detail.byline, style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Accent), textAlign = TextAlign.Center)
                    }
                    if (detail.narrator.isNotBlank()) {
                        Text(stringResource(UiR.string.detail_narrated_by, detail.narrator), style = Antresol.typography.caption, textAlign = TextAlign.Center)
                    }
                    val meta = listOfNotNull(
                        detail.year?.toString(),
                        detail.genres.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(", "),
                        detail.songCount?.takeIf { it > 0 }?.let { pluralStringResource(UiR.plurals.song_count, it, it) },
                        detail.durationMs?.let { spokenDuration(it) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = Antresol.typography.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item("actions") { MediaActions(detail, viewModel, playback, onRead) }
        if (card.kind == Kind.PLAYLIST) {
            item("playlist-actions") { PlaylistActions(viewModel, onPlaylistDialog) }
            if (detail.tracks.isEmpty()) item("playlist-empty") { EmptyView(stringResource(R.string.playlist_empty)) }
        }
        if (detail.description.isNotBlank()) item("description") { ExpandableText(detail.description) }
        if (detail.tracks.isNotEmpty()) {
            item("tracks-title") { SectionTitle(stringResource(UiR.string.detail_tracks), Modifier.padding(top = 16.dp)) }
            val editing = card.kind == Kind.PLAYLIST && viewModel.editing
            itemsIndexed(detail.tracks, key = { index, track -> "track-$index-${track.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    number = if (detail.tracksShowArtist) index + 1 else track.trackNo.takeIf { it > 0 } ?: (index + 1),
                    showArtist = detail.tracksShowArtist || card.kind == Kind.ARTIST,
                    playing = playback.current?.trackId == track.id,
                    onClick = { playback.playTracks(detail.tracks, index) },
                    onLongClick = { viewModel.playlistPicker.open(listOf(track.id)) },
                    trailing = if (editing) {
                        { PlaylistTrackControls(index, detail.tracks.size, viewModel) }
                    } else {
                        null
                    },
                )
            }
        }
        if (detail.chapters.isNotEmpty()) {
            item("chapters-title") { SectionTitle(stringResource(UiR.string.detail_chapters), Modifier.padding(top = 16.dp)) }
            val book = detail.book
            val playingThis = book != null && playback.current?.bookId == book.card.id
            val currentChapter = if (playingThis) playback.currentChapter else null
            items(detail.chapters, key = { "chapter-${it.idx}" }) { chapter ->
                ChapterRow(chapter, current = chapter == currentChapter) {
                    when {
                        book == null -> Unit
                        playingThis -> playback.playChapter(chapter)
                        else -> playback.playAudiobook(book, chapter.startMs)
                    }
                }
            }
        }
        items(detail.rows, key = { it.id }) { row ->
            Shelf(title = stringResource(row.titleRes), cards = row.cards, onOpen = onOpen)
        }
    }
}

/** "Listen from Beginning" / "Read from Beginning" next to Resume or Continue Reading. */
@Composable
private fun RestartButton(action: BookAction?, book: BookDetail?, playback: PlaybackConnection, onRead: (BookAction) -> Unit) {
    val restart: Pair<Int, () -> Unit> = when (action) {
        is BookAction.Listen -> if (action.resume && book != null) R.string.detail_listen_from_start to { playback.playAudiobook(book, 0L) } else null
        is BookAction.ReadComic -> if (action.resume) R.string.detail_read_from_start to { onRead(action.copy(fromStart = true)) } else null
        is BookAction.ReadPdf -> if (action.resume) R.string.detail_read_from_start to { onRead(action.copy(fromStart = true)) } else null
        is BookAction.Unsupported, null -> null
    } ?: return
    GlassIconButton(
        icon = AntresolIcons.Restart,
        contentDescription = stringResource(restart.first),
        onClick = restart.second,
        modifier = Modifier.size(50.dp).testTag("book-from-start"),
    )
}

/** Edit (or Done), Rename and Delete under a playlist's Play and Shuffle. */
@Composable
private fun PlaylistActions(viewModel: MediaDetailViewModel, onDialog: (PlaylistDialog) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        GlassPillButton(
            text = stringResource(if (viewModel.editing) R.string.playlist_done else R.string.playlist_edit),
            icon = if (viewModel.editing) MobileIcons.Check else null,
            onClick = viewModel::toggleEditing,
            modifier = Modifier.testTag("playlist-edit"),
        )
        GlassPillButton(text = stringResource(R.string.playlist_rename), onClick = { onDialog(PlaylistDialog.Rename) }, modifier = Modifier.testTag("playlist-rename"))
        GlassPillButton(
            text = stringResource(UiR.string.playlist_delete),
            onClick = { onDialog(PlaylistDialog.Delete) },
            enabled = !viewModel.savingPlaylist,
            modifier = Modifier.testTag("playlist-delete"),
        )
    }
}

/** Move up, move down and remove for the song at [index] while a playlist is edited. */
@Composable
private fun PlaylistTrackControls(index: Int, count: Int, viewModel: MediaDetailViewModel) {
    val enabled = !viewModel.savingPlaylist
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(
            icon = MobileIcons.ChevronUp,
            contentDescription = stringResource(R.string.playlist_move_up),
            onClick = { if (enabled && index > 0) viewModel.moveTrack(index, index - 1) },
            tint = if (index > 0) AntresolColors.Label else AntresolColors.LabelTertiary,
            modifier = Modifier.testTag("playlist-up-$index"),
        )
        GlassIconButton(
            icon = MobileIcons.ChevronDown,
            contentDescription = stringResource(R.string.playlist_move_down),
            onClick = { if (enabled && index < count - 1) viewModel.moveTrack(index, index + 1) },
            tint = if (index < count - 1) AntresolColors.Label else AntresolColors.LabelTertiary,
            modifier = Modifier.testTag("playlist-down-$index"),
        )
        GlassIconButton(
            icon = MobileIcons.MinusCircle,
            contentDescription = stringResource(R.string.playlist_remove),
            onClick = { if (enabled) viewModel.removeTrack(index) },
            tint = AntresolColors.Red,
            modifier = Modifier.testTag("playlist-remove-$index"),
        )
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, onClick = onClick)
            .testTag("chapter-${chapter.idx}")
            .padding(horizontal = PageGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            chapter.title,
            style = Antresol.typography.body.copy(color = if (current) AntresolColors.Accent else AntresolColors.Label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(Durations.clock(chapter.startMs), style = Antresol.typography.caption)
    }
}

/** Play/Shuffle for music, Listen/Read plus Want to Read for books, or a note for formats the app cannot open. */
@Composable
private fun MediaActions(detail: MediaDetail, viewModel: MediaDetailViewModel, playback: PlaybackConnection, onRead: (BookAction) -> Unit) {
    val card = detail.card
    when (card.kind) {
        Kind.ALBUM, Kind.PLAYLIST -> if (detail.tracks.isNotEmpty()) {
            PlayShuffleRow(
                onPlay = { playback.playTracks(detail.tracks) },
                onShuffle = { playback.playTracks(detail.tracks, shuffled = true) },
            ) {
                if (card.kind == Kind.ALBUM) {
                    GlassIconButton(
                        icon = MobileIcons.PlaylistAdd,
                        contentDescription = stringResource(UiR.string.playlist_add),
                        onClick = { viewModel.playlistPicker.open(detail.tracks.map { it.id }) },
                        modifier = Modifier.size(50.dp).testTag("album-add-to-playlist"),
                    )
                }
                detail.starred?.let { starred ->
                    GlassIconButton(
                        icon = if (starred) MobileIcons.StarFilled else MobileIcons.Star,
                        contentDescription = stringResource(if (starred) R.string.now_playing_unstar else R.string.now_playing_star),
                        onClick = viewModel::toggleStar,
                        tint = if (starred) AntresolColors.Accent else AntresolColors.Label,
                        modifier = Modifier.size(50.dp).testTag("album-star"),
                    )
                }
            }
        }
        // Play needs top tracks; Shuffle uses the server's shuffle of everything by the artist.
        Kind.ARTIST -> PlayShuffleRow(
            onPlay = if (detail.tracks.isNotEmpty()) ({ playback.playTracks(detail.tracks) }) else null,
            onShuffle = { viewModel.shuffleArtist { tracks -> playback.playTracks(tracks) } },
            shuffleEnabled = !viewModel.shuffling,
        )
        else -> {
            val book = detail.book
            val action = book?.let { BookAction.of(it) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    when (action) {
                        is BookAction.Listen -> PrimaryPillButton(
                            text = stringResource(if (action.resume) UiR.string.action_resume else UiR.string.action_listen),
                            icon = AntresolIcons.Play,
                            onClick = {
                                if (action.resume) playback.resumeAudiobook(book, action.fromMs) else playback.playAudiobook(book, 0L)
                            },
                            modifier = Modifier.weight(1f, fill = false).testTag("book-listen"),
                        )
                        is BookAction.ReadComic, is BookAction.ReadPdf -> {
                            val resume = (action as? BookAction.ReadComic)?.resume ?: (action as BookAction.ReadPdf).resume
                            PrimaryPillButton(
                                text = stringResource(if (resume) R.string.detail_continue_reading else UiR.string.action_read),
                                icon = MobileIcons.Book,
                                onClick = { onRead(action) },
                                modifier = Modifier.weight(1f, fill = false).testTag("book-read"),
                            )
                        }
                        is BookAction.Unsupported, null -> Unit
                    }
                    RestartButton(action, book, playback, onRead)
                    detail.want?.let { want ->
                        GlassPillButton(
                            text = stringResource(R.string.detail_want),
                            icon = if (want) MobileIcons.Check else MobileIcons.Plus,
                            onClick = viewModel::toggleWant,
                            modifier = Modifier.weight(1f, fill = false).testTag("toggle-want"),
                        )
                    }
                }
                if (action is BookAction.Unsupported) {
                    Text(
                        stringResource(R.string.detail_format_coming_soon, action.format.ifBlank { "?" }),
                        style = Antresol.typography.caption,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
