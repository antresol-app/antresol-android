package app.antresol.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.CastMember
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.FileInfo
import app.antresol.core.model.Kind
import app.antresol.core.model.AddonWay
import app.antresol.core.model.Trailer
import app.antresol.core.model.KidAsk
import app.antresol.core.ways.WayCall
import app.antresol.core.ways.WayPlayback
import app.antresol.core.ways.WaysController
import app.antresol.core.library.EpisodeState
import app.antresol.core.library.GuideEpisode
import app.antresol.mobile.R
import app.antresol.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.ActionFlow
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.OpenedPoster
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.posterBounds
import app.antresol.mobile.ui.components.rememberOpenedPoster
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.RefreshBox
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.components.pageListPadding
import app.antresol.mobile.ui.components.PageSideInsets
import app.antresol.ui.components.kindLabel
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.components.CastPhoto
import app.antresol.ui.components.EpisodeMarks
import app.antresol.ui.components.WatchedStyle
import app.antresol.ui.trailers.Trailers
import app.antresol.ui.trailers.trailerLabel
import app.antresol.mobile.player.TrailerPlayerActivity
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.format.Durations
import app.antresol.ui.format.EpisodeLabels
import app.antresol.ui.format.episodeStateLabel
import app.antresol.ui.format.seasonLabel
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.playback.ResumeMode
import app.antresol.ui.components.wayIcon
import app.antresol.ui.requests.RequestStatusPill
import app.antresol.ui.requests.TitleRequest
import app.antresol.ui.ways.Attach
import app.antresol.ui.ways.busyState
import app.antresol.ui.ways.offerState
import app.antresol.ui.ways.pickerState
import app.antresol.ui.ways.statesState
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage
import java.util.Locale

/**
 * Movie, series, season and episode detail: tinted backdrop header with title and meta, the
 * Want to Watch and Watched toggles, overview, Up Next, a season picker with episode rows
 * (each with its own watched toggle), file details and cast.
 */
@Composable
fun VideoDetailScreen(
    viewModel: ItemDetailViewModel,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
    onPlay: (itemId: Long, mode: ResumeMode) -> Unit,
    /** Plays what an addon's way answered with (see [WayPlayback]). */
    onPlayWay: (WayPlayback) -> Unit = {},
    /** The title page was opened for something the library has: show the library's own page instead. */
    onOpenLibraryItem: (Card) -> Unit = {},
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        viewModel.ways?.refreshStates()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    NoticeSnackbar(viewModel.notice, snackbar, viewModel::consumeNotice)
    // The addons' ways of this title: attached when the page is read; what they answer reaches the player or a notice.
    val scope = rememberCoroutineScope()
    val notices = remember(scope, snackbar) { NoticeSnackbars(scope, snackbar) }
    val loaded = (viewModel.state as? LoadState.Ready)?.value
    viewModel.ways.Attach(loaded?.wayTarget, LocalContext.current.resources, onPlay = onPlayWay, onText = notices::show)
    // A kid's «Ask a Parent»: the request state of the title as the server said it with the page.
    val kidRequest = loaded?.title?.kidRequest
    LaunchedEffect(kidRequest) { viewModel.request?.load(kidRequest) }
    loaded?.libraryItemId?.let { libraryId ->
        LaunchedEffect(libraryId) { onOpenLibraryItem(Card(id = libraryId, kind = loaded.item.kind, title = loaded.item.title)) }
    }
    // The tile this page was opened from: its picture grows into the page's artwork, and back on Back.
    val poster = rememberOpenedPoster()
    RefreshBox(viewModel.refreshing, onRefresh = viewModel::pullToRefresh, Modifier.fillMaxSize().background(AntresolColors.Background)) {
        when (val state = viewModel.state) {
            LoadState.Loading -> DetailStateBox(onBack, art = poster?.let { { PosterLoadingArt(it) } }) { LoadingView() }
            is LoadState.Failed -> DetailStateBox(onBack) { ErrorView(state.error, onRetry = viewModel::reload) }
            is LoadState.Ready -> VideoDetailContent(state.value, viewModel, onOpen, onBack, onPlay, poster)
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                // The page is not padded as a whole (its header goes edge to edge): keep clear of the cutout.
                .windowInsetsPadding(PageSideInsets)
                .padding(LocalBottomBarPadding.current),
        )
    }
}

@Composable
private fun VideoDetailContent(
    detail: VideoDetail,
    viewModel: ItemDetailViewModel,
    onOpen: (Card) -> Unit,
    onBack: () -> Unit,
    onPlay: (itemId: Long, mode: ResumeMode) -> Unit,
    poster: OpenedPoster?,
) {
    val item = detail.item
    val urls = LocalMediaUrls.current
    val configuration = LocalConfiguration.current
    val bottom = LocalBottomBarPadding.current
    val librarySeasons = item.seasons.filter { it.episodes.isNotEmpty() }
    // Every episode of the guide (or the library's when the server has none); recomputed after
    // each watched toggle, which changes the item.
    val guide = remember(detail) { detail.episodeGuide() }
    val seasons = guide.seasons
    val initialSeason = item.next?.let { next -> seasons.firstOrNull { s -> s.libraryEpisodes.any { it.id == next.id } } }
        ?: seasons.firstOrNull { it.libraryEpisodes.isNotEmpty() }
        ?: seasons.firstOrNull()
    var selectedSeason by rememberSaveable(item.id) { mutableIntStateOf(initialSeason?.number ?: 0) }
    val season = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.firstOrNull()
    // The addons' ways: the watch ways join Play (or are its first button), the keep ways are round buttons.
    val ways = viewModel.ways
    val offer = ways.offerState()
    val wayStates = ways.statesState()
    val wayBusy = ways.busyState()
    val picker = ways.pickerState()
    val wayTarget = detail.wayTarget
    var waysSheet by remember { mutableStateOf<WaysChoice?>(null) }
    var keepMenu by remember { mutableStateOf<AddonWay?>(null) }
    val runWay: (AddonWay, Int?, Int?, Boolean) -> Unit = { way, season, episode, choose ->
        wayTarget?.let { ways?.start(WayCall(way, it, season, episode), choose) }
    }
    // A sheet opened for a run stays until the run answered (a player, a notice or the choice).
    LaunchedEffect(wayBusy, waysSheet?.started) {
        if (waysSheet?.started == true && ways?.busy?.value == null) waysSheet = null
    }
    val episodeWays: (GuideEpisode) -> List<AddonWay> = remember(offer, wayTarget) {
        { episode ->
            when {
                wayTarget == null || item.kind != Kind.SERIES || ways == null -> emptyList()
                episode.state == EpisodeState.Upcoming -> emptyList()
                else -> ways.forEpisode(inLibrary = episode.state == EpisodeState.Downloaded)
            }
        }
    }
    val watchWays = offer.watch
    val primaryWay = watchWays.firstOrNull().takeIf { detail.playTarget == null }
    val rowWays = WaysRowState(
        primary = primaryWay,
        showMore = watchWays.size > (if (primaryWay != null) 1 else 0),
        keep = offer.keep,
        states = wayStates,
        busy = wayBusy,
        onRun = { way -> runWay(way, null, null, false) },
        onChoose = { way -> runWay(way, null, null, true) },
        onMore = { waysSheet = WaysChoice(watchWays, null, null) },
        onKeep = { way ->
            val state = wayStates[way.key]
            if (state?.active == true && state.cancel) keepMenu = way else runWay(way, null, null, false)
        },
        onKeepChoose = { way -> runWay(way, null, null, true) },
    )
    // One trailer plays at once; several open the chooser.
    var trailerSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val trailers = viewModel.trailers
    val openTrailer: (Trailer) -> Unit = { trailer ->
        trailerSheet = false
        when (Trailers.howToPlay(trailer)) {
            Trailers.Mode.Player, Trailers.Mode.Embed ->
                TrailerPlayerActivity.start(context, trailer.play, trailer.key, trailerLabel(context.resources, trailer))
            Trailers.Mode.External -> if (!Trailers.open(context, trailer)) viewModel.showNotice(UiR.string.trailer_no_app)
        }
    }
    val onTrailer: () -> Unit = { if (trailers.size == 1) openTrailer(trailers.single()) else trailerSheet = true }
    if (trailerSheet) TrailerSheet(trailers, onDismiss = { trailerSheet = false }, onOpen = openTrailer)
    waysSheet?.let { choice ->
        WaysSheet(
            ways = choice.ways,
            // Only the run for this very title or episode spins; another episode's does not.
            isBusy = { way -> wayBusy?.isFor(way, choice.season, choice.episode) == true },
            onRun = { way ->
                waysSheet = choice.copy(started = true)
                runWay(way, choice.season, choice.episode, false)
            },
            onChoose = { way ->
                waysSheet = choice.copy(started = true)
                runWay(way, choice.season, choice.episode, true)
            },
            onDismiss = { waysSheet = null },
        )
    }
    keepMenu?.let { way ->
        KeepMenuSheet(way, wayStates[way.key], onCancel = { ways?.cancel(way) }, onDismiss = { keepMenu = null })
    }
    picker?.let { open ->
        WayStepSheet(open, onChoose = { row -> ways?.choose(row) }, onBack = { ways?.back() }, onDismiss = { ways?.dismiss() })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageListPadding(bottom.calculateBottomPadding() + 24.dp),
    ) {
        item("header") {
            val artHeight = videoArtHeight(configuration)
            DetailHeader(
                tintUrl = urls?.image(item.poster.ifBlank { item.backdrop }),
                onBack = onBack,
                artwork = {
                    val art = urls?.image(item.backdrop.ifBlank { item.poster })
                    if (art != null) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.posterBounds(poster?.key, AntresolShapes.card).fillMaxWidth().height(artHeight + 60.dp).alpha(0.85f),
                        )
                    }
                    Box(Modifier.matchParentSize().background(AntresolGradients.heroFade))
                },
            ) {
                Spacer(Modifier.height(artHeight - 120.dp))
                val eyebrow = if (item.kind == Kind.EPISODE) item.subtitle else kindLabel(item.kind).orEmpty()
                val seriesId = item.seriesId?.takeIf { it > 0 && (item.kind == Kind.EPISODE || item.kind == Kind.SEASON) }
                if (seriesId != null) {
                    // Episodes and seasons lead back to their show (opened from search or Continue Watching).
                    val goToSeries = stringResource(R.string.detail_go_to_series)
                    Row(
                        modifier = Modifier
                            .clip(AntresolShapes.pill)
                            .focusClickable(AntresolShapes.pill) { onOpen(Card(id = seriesId, kind = Kind.SERIES, title = "")) }
                            .semantics { contentDescription = goToSeries }
                            .padding(vertical = 4.dp)
                            .testTag("detail-go-to-series"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(eyebrow.ifBlank { goToSeries }.uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow)
                        Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.LabelSecondary, modifier = Modifier.size(16.dp))
                    }
                } else if (eyebrow.isNotBlank()) {
                    Text(eyebrow.uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow)
                }
                Text(item.title, style = Antresol.typography.heroTitle, modifier = Modifier.testTag("detail-title"))
                val runtime = item.runtime?.let { it * 60_000L } ?: item.files.firstOrNull()?.durationMs
                val meta = listOfNotNull(
                    item.year?.toString(),
                    runtime?.takeIf { it > 0 }?.let { spokenDuration(it) },
                    item.rating?.takeIf { it.isNotBlank() },
                    item.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = Antresol.typography.caption)
                BadgeRow(item.badges, max = 4)
            }
        }
        if (detail.playTarget != null || primaryWay != null || rowWays.showMore || rowWays.keep.isNotEmpty()) {
            item("play") {
                PlayRow(
                    detail.playTarget,
                    onPlay,
                    // Nothing to play: the trailer takes a pill of its own in the actions below.
                    onTrailer = onTrailer.takeIf { trailers.isNotEmpty() && detail.playTarget != null },
                    ways = rowWays,
                )
            }
        }
        // A kid's page of a title the library does not have: «Ask a Parent», then the state of the request.
        viewModel.request?.takeIf { detail.outsideLibrary && detail.title?.kidRequest != null }?.let { request ->
            item("request") {
                RequestBlock(request, onAsk = { viewModel.request?.ask(KidAsk(kind = item.kind, tmdbId = item.tmdbId ?: 0, title = item.title)) })
            }
        }
        // A title off the library has no watchlist, watched state or trailers: nothing to put in this row.
        if (!detail.outsideLibrary) item("actions") {
            // Each pill keeps its whole label on one line: one that does not fit beside the others goes below them.
            ActionFlow(modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 16.dp)) {
                val buttonModifier = if (configuration.screenWidthDp >= 600) Modifier else Modifier.grow()
                if (detail.canWatchlist) {
                    GlassPillButton(
                        text = stringResource(R.string.detail_watchlist),
                        icon = if (item.inWatchlist) MobileIcons.Check else MobileIcons.Plus,
                        onClick = viewModel::toggleWatchlist,
                        modifier = buttonModifier.testTag("toggle-watchlist"),
                    )
                }
                if (item.kind != Kind.SERIES || librarySeasons.isNotEmpty()) {
                    GlassPillButton(
                        text = stringResource(UiR.string.detail_watched),
                        icon = if (detail.watched) MobileIcons.CheckCircle else MobileIcons.Circle,
                        onClick = viewModel::toggleWatched,
                        modifier = buttonModifier.testTag("toggle-watched"),
                    )
                }
                // Nothing to play: the trailer takes a pill here instead of the icon beside Play.
                if (detail.playTarget == null && trailers.isNotEmpty()) {
                    GlassPillButton(
                        text = stringResource(UiR.string.action_trailer),
                        icon = AntresolIcons.Trailer,
                        onClick = onTrailer,
                        modifier = buttonModifier.testTag("detail-trailer"),
                    )
                }
            }
        }
        if (item.overview.isNotBlank()) item("overview") { ExpandableText(item.overview) }
        item.next?.takeIf { item.kind == Kind.SERIES }?.let { next ->
            item("next") {
                SectionTitle(stringResource(R.string.detail_up_next), Modifier.padding(top = 12.dp))
                EpisodeRow(next, onOpen = { onOpen(next.toCard()) }, onPlay = { onPlay(next.id, ResumeMode.Ask) }, onToggleWatched = { viewModel.toggleEpisodeWatched(next) })
            }
        }
        if (season != null) {
            item("seasons") {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionTitle(stringResource(if (seasons.size > 1) UiR.string.detail_seasons else R.string.detail_episodes))
                    if (seasons.size > 1) {
                        SegmentedPills(
                            options = seasons,
                            selected = season,
                            label = { seasonLabel(it) },
                            onSelect = { selectedSeason = it.number },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    val seasonId = season.libraryId
                    val libraryEpisodes = season.libraryEpisodes
                    if (seasonId != null && libraryEpisodes.isNotEmpty()) {
                        SeasonWatchedToggle(libraryEpisodes) { viewModel.toggleSeasonWatched(seasonId, libraryEpisodes) }
                    }
                }
            }
            items(season.episodes, key = { it.key }) { episode ->
                val card = episode.card
                val mark = episodeStateLabel(episode, guide.fromServer)
                val ofEpisode = episodeWays(episode)
                val chooseWay: (() -> Unit)? = if (ofEpisode.isEmpty()) null else {
                    { waysSheet = WaysChoice(ofEpisode, episode.season, episode.number) }
                }
                if (card != null && episode.isPlayable) {
                    EpisodeRow(
                        card,
                        onOpen = { onOpen(card.toCard()) },
                        onPlay = { onPlay(card.id, ResumeMode.Ask) },
                        onToggleWatched = { viewModel.toggleEpisodeWatched(card) },
                        code = episode.season to episode.number,
                        mark = mark,
                        isNew = episode.isNew,
                        onLongClick = chooseWay,
                    )
                } else {
                    UnavailableEpisodeRow(
                        episode,
                        mark = mark,
                        playable = ofEpisode.isNotEmpty(),
                        onClick = if (episode.state == EpisodeState.NotDownloaded) {
                            // The ways of an episode that is not in the library: a tap opens them, else it says why it does not play.
                            chooseWay ?: { viewModel.showNotice(UiR.string.episode_not_in_library) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        item.files.firstOrNull()?.let { file -> item("file") { FileDetails(file) } }
        if (item.cast.isNotEmpty()) item("cast") { CastRow(item.cast) }
    }
}

@Composable
private fun SeasonWatchedToggle(episodes: List<EpisodeCard>, onToggle: () -> Unit) {
    val allWatched = episodes.all { it.watched }
    Text(
        text = stringResource(if (allWatched) R.string.detail_mark_season_unwatched else R.string.detail_mark_season_watched),
        style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Accent),
        modifier = Modifier
            .padding(horizontal = PageGutter - 8.dp)
            .clip(AntresolShapes.pill)
            .focusClickable(AntresolShapes.pill, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** «Ask a Parent» (white pill) or, once asked, the state of the request; under it why not, and what went wrong. */
@Composable
private fun RequestBlock(request: TitleRequest, onAsk: () -> Unit) {
    val made = request.request
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (made != null) {
            RequestStatusPill(made)
        } else {
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            val label = stringResource(if (request.busy) UiR.string.request_asking else UiR.string.request_ask)
            if (request.canAsk) {
                PrimaryPillButton(
                    text = label,
                    icon = AntresolIcons.Send,
                    enabled = !request.busy,
                    onClick = onAsk,
                    modifier = (if (wide) Modifier.widthIn(min = 220.dp) else Modifier.fillMaxWidth()).testTag("request-ask"),
                )
            } else {
                // Above the profile's limit: the pill stays, greyed, with the server's reason beneath.
                GlassPillButton(
                    text = label,
                    icon = AntresolIcons.Send,
                    enabled = false,
                    onClick = {},
                    modifier = (if (wide) Modifier.widthIn(min = 220.dp) else Modifier.fillMaxWidth()).testTag("request-ask"),
                )
            }
            request.why?.let { Text(it, style = Antresol.typography.caption, modifier = Modifier.testTag("request-note")) }
            if (request.failed) Text(stringResource(UiR.string.request_ask_failed), style = Antresol.typography.caption.copy(color = AntresolColors.Red))
        }
    }
}

/** The white Play/Resume pill ("Resume S1 E3" for a series) and, when resuming, Play from Beginning. */
@Composable
private fun PlayRow(
    target: PlayTarget?,
    onPlay: (itemId: Long, mode: ResumeMode) -> Unit,
    onTrailer: (() -> Unit)? = null,
    ways: WaysRowState? = null,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val primary = ways?.primary
    Column(Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The main button keeps its whole label on one line: when the buttons beside it do not fit, they go below it.
        ActionFlow(modifier = Modifier.fillMaxWidth(), firstAlone = true) {
            if (target != null) {
                val episode = target.episode
                val label = when {
                    episode != null && target.resume -> stringResource(UiR.string.detail_resume_episode, episode.seasonNo, episode.episodeNo)
                    episode != null -> stringResource(UiR.string.detail_play_episode, episode.seasonNo, episode.episodeNo)
                    target.resume -> stringResource(UiR.string.action_resume)
                    else -> stringResource(UiR.string.action_play)
                }
                PrimaryPillButton(
                    text = label,
                    icon = AntresolIcons.Play,
                    onClick = { onPlay(target.itemId, ResumeMode.Resume) },
                    modifier = (if (wide) Modifier.widthIn(min = 220.dp) else Modifier.grow()).testTag("detail-play"),
                )
                if (target.resume) {
                    GlassIconButton(
                        icon = AntresolIcons.Restart,
                        contentDescription = stringResource(UiR.string.action_play_from_start),
                        onClick = { onPlay(target.itemId, ResumeMode.FromStart) },
                        modifier = Modifier.size(50.dp).testTag("detail-play-from-start"),
                    )
                }
            } else if (primary != null && ways != null) {
                // Nothing of the library plays: the first way of the addons is the main button.
                // Only a run of this very button (the title itself) shows «Opening…»; one of an episode does not.
                val working = ways.busy?.isFor(primary, null, null) == true
                PrimaryPillButton(
                    text = if (working) stringResource(UiR.string.way_busy) else primary.label,
                    icon = wayIcon(primary.icon),
                    enabled = ways.busy == null,
                    onClick = { ways.onRun(primary) },
                    onLongClick = { ways.onChoose(primary) },
                    modifier = (if (wide) Modifier.widthIn(min = 220.dp) else Modifier.grow()).testTag("way-primary"),
                )
            }
            if (onTrailer != null) {
                GlassIconButton(
                    icon = AntresolIcons.Trailer,
                    contentDescription = stringResource(UiR.string.action_trailer),
                    onClick = onTrailer,
                    modifier = Modifier.size(50.dp).testTag("detail-trailer"),
                )
            }
            if (ways != null && ways.showMore) {
                GlassIconButton(
                    icon = AntresolIcons.ChevronDown,
                    contentDescription = stringResource(UiR.string.way_more),
                    onClick = ways.onMore,
                    modifier = Modifier.size(50.dp).testTag("ways-more"),
                )
            }
            ways?.keep?.forEach { way ->
                KeepWayButton(
                    way = way,
                    state = ways.states[way.key],
                    working = ways.busy?.isFor(way, null, null) == true,
                    onClick = { ways.onKeep(way) },
                    onLongClick = { ways.onKeepChoose(way) },
                )
            }
        }
        // What a keep way says about itself («Saved 40%», and «Saved» once it is done), in the server's words.
        ways?.keep?.forEach { way ->
            ways.states[way.key]?.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Antresol.typography.caption, modifier = Modifier.testTag("way-keep-text-${way.id}"))
            }
        }
    }
}

/** What the ways of the addons add to the play row of a title. */
private class WaysRowState(
    /** The first watch way, as the main button when nothing of the library plays. */
    val primary: AddonWay?,
    /** There are watch ways to list under «more ways». */
    val showMore: Boolean,
    val keep: List<AddonWay>,
    val states: Map<String, app.antresol.core.model.WayState>,
    val busy: app.antresol.core.model.WayRun?,
    val onRun: (AddonWay) -> Unit,
    val onChoose: (AddonWay) -> Unit,
    val onMore: () -> Unit,
    val onKeep: (AddonWay) -> Unit,
    val onKeepChoose: (AddonWay) -> Unit,
)

/** The ways a sheet lists, for the title ([season] and [episode] null) or for one episode of it. */
private data class WaysChoice(val ways: List<AddonWay>, val season: Int?, val episode: Int?, val started: Boolean = false)

/**
 * An episode: still with progress (tap to play), "S1 · E2", title, length and overview (tap for
 * the episode page), and a watched toggle. [code] is the season and number to show (the episode's
 * own by default), [mark] the guide's «Скачана».
 */
@Composable
private fun EpisodeRow(
    episode: EpisodeCard,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    code: Pair<Int, Int> = episode.seasonNo to episode.episodeNo,
    mark: String? = null,
    isNew: Boolean = false,
    /** The ways of the addons for this episode, by a long press. */
    onLongClick: (() -> Unit)? = null,
) {
    val urls = LocalMediaUrls.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val toggleLabel = stringResource(if (episode.watched) UiR.string.action_mark_unwatched else UiR.string.action_mark_watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, onLongClick = onLongClick, onClick = onOpen)
            .padding(horizontal = PageGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            // The episode's own still or a frame of it; the series backdrop only when the server has neither.
            url = urls?.image(episode.wideImage),
            fallbackUrl = urls?.image(episode.wideFallback),
            title = episode.title,
            shape = AntresolShapes.smallCard,
            modifier = Modifier
                .width(if (wide) 200.dp else 136.dp)
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT)
                .clip(AntresolShapes.smallCard)
                .focusClickable(AntresolShapes.smallCard, onClick = onPlay)
                .alpha(WatchedStyle.artworkAlpha(episode.watched))
                .testTag("episode-play-${episode.id}"),
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .background(AntresolColors.Glass.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AntresolIcons.Play, contentDescription = stringResource(UiR.string.action_play), tint = AntresolColors.Label, modifier = Modifier.size(16.dp))
            }
            if (episode.progress > 0.0 && !episode.watched) {
                ProgressBar(episode.progress.toFloat(), Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.detail_episode_code, code.first, code.second),
                style = Antresol.typography.eyebrow,
            )
            Text(episode.title, style = Antresol.typography.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            episode.durationMs?.takeIf { it > 0 }?.let { Text(spokenDuration(it), style = Antresol.typography.caption) }
            EpisodeMarks(isNew = isNew, state = mark, modifier = Modifier.padding(top = 2.dp))
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Antresol.typography.caption, maxLines = if (wide) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .focusClickable(CircleShape, role = Role.Checkbox, onClick = onToggleWatched)
                .semantics { contentDescription = toggleLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (episode.watched) MobileIcons.CheckCircle else MobileIcons.Circle,
                contentDescription = null,
                tint = if (episode.watched) AntresolColors.Accent else AntresolColors.LabelTertiary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * An episode the library does not have: the same row as [EpisodeRow] without the still, Play and
 * watched toggle, dimmed (not downloaded) or greyed (not aired yet, [onClick] null), with its mark.
 * A not downloaded one an addon's way plays ([playable]) is not dimmed.
 */
@Composable
private fun UnavailableEpisodeRow(episode: GuideEpisode, mark: String?, playable: Boolean, onClick: (() -> Unit)?) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val tag = if (episode.state == EpisodeState.Upcoming) "episode-upcoming" else "episode-missing"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.focusClickable(AntresolShapes.control, onClick = onClick) else Modifier)
            .padding(horizontal = PageGutter, vertical = 8.dp)
            .alpha(EpisodeLabels.alpha(episode.state, playable))
            .testTag("$tag-${episode.season}-${episode.number}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = null,
            title = episode.title,
            shape = AntresolShapes.smallCard,
            modifier = Modifier
                .width(if (wide) 200.dp else 136.dp)
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.detail_episode_code, episode.season, episode.number),
                style = Antresol.typography.eyebrow,
            )
            Text(
                episode.title.ifBlank { stringResource(UiR.string.detail_episode_number, episode.number) },
                style = Antresol.typography.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            EpisodeMarks(isNew = episode.isNew, state = mark, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (episode.watched) {
                Icon(
                    imageVector = MobileIcons.CheckCircle,
                    contentDescription = stringResource(UiR.string.detail_watched),
                    tint = AntresolColors.Accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Resolution, audio and subtitle tracks of the first file. */
@Composable
private fun FileDetails(file: FileInfo) {
    val resources = LocalContext.current.resources
    Column(Modifier.padding(top = 20.dp)) {
        SectionTitle(stringResource(R.string.detail_about))
        InfoLine(stringResource(R.string.detail_file), listOfNotNull(file.resolution, file.durationMs?.let(Durations::clock)).joinToString(" · ").ifBlank { file.name })
        if (file.audio.isNotEmpty()) {
            InfoLine(stringResource(UiR.string.detail_audio), file.audio.joinToString("\n") { TrackLabels.audio(resources, it) })
        }
        if (file.subs.isNotEmpty()) {
            InfoLine(stringResource(UiR.string.detail_subtitles), file.subs.joinToString("\n") { TrackLabels.subtitle(resources, it) })
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    val urls = LocalMediaUrls.current
    Column(Modifier.padding(top = 20.dp)) {
        SectionTitle(stringResource(UiR.string.detail_cast))
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The same person can be listed twice: the position keeps keys unique.
            itemsIndexed(cast, key = { index, member -> "$index-${member.name}" }) { _, member ->
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CastPhoto(member, urls?.image(member.photo), size = 64.dp)
                    Text(member.name, style = Antresol.typography.caption.copy(color = AntresolColors.Label), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    member.character?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
