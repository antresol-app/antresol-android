package app.antresol.tv.ui.comics

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Pages loaded ahead of (and one behind) the current page. */
private const val PREFETCH_AHEAD = 2

/** How long the page counter stays up after a page turn. */
private const val OVERLAY_MS = 2_500L

private val PROMPT_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

/**
 * The comic reader: one page at a time, fitted to the screen. Left/Right turn pages (swapped for
 * right-to-left manga), OK shows the page counter (or retries a page that failed), Back leaves. Pages load with the Bearer header
 * through the app's image loader, the next ones in advance. Past the last page it offers the next volume.
 */
@Composable
fun ComicReaderScreen(viewModel: ComicReaderViewModel, onOpenVolume: (Long) -> Unit, onExit: () -> Unit) {
    when (val state = viewModel.state) {
        LoadState.Loading -> LoadingView()
        is LoadState.Failed -> ErrorView(state.error, onRetry = viewModel::reload)
        is LoadState.Ready -> {
            val volume = state.value
            if (volume.count == 0 || volume.fileId == 0L) MessageView(stringResource(TvR.string.comic_no_pages)) else Reader(volume, viewModel, onOpenVolume, onExit)
        }
    }
}

@Composable
private fun Reader(volume: ComicVolume, viewModel: ComicReaderViewModel, onOpenVolume: (Long) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current ?: return
    val page = viewModel.page
    val focus = remember { FocusRequester() }
    var overlayToken by remember { mutableIntStateOf(1) }
    var overlay by remember { mutableStateOf(true) }
    var failed by remember(page) { mutableStateOf(false) }
    var attempt by remember(page) { mutableIntStateOf(0) }

    LaunchedEffect(viewModel.atEnd) { if (!viewModel.atEnd) focus.tryRequestFocus() }
    LaunchedEffect(overlayToken) {
        overlay = true
        delay(OVERLAY_MS)
        overlay = false
    }
    LaunchedEffect(page) {
        val loader = context.imageLoader
        ((page + 1)..(page + PREFETCH_AHEAD)).plus(page - 1)
            .filter { it in 0 until volume.count }
            .forEach { loader.enqueue(ImageRequest.Builder(context).data(urls.comicPage(volume.fileId, it)).build()) }
    }
    BackHandler(enabled = viewModel.atEnd) { viewModel.dismissEnd() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // The end-of-volume prompt's buttons need the D-pad and OK for themselves.
                if (viewModel.atEnd && event.key in PROMPT_KEYS) return@onKeyEvent false
                val forward = if (volume.isRightToLeft) Key.DirectionLeft else Key.DirectionRight
                val backward = if (volume.isRightToLeft) Key.DirectionRight else Key.DirectionLeft
                when (event.key) {
                    forward, Key.MediaFastForward, Key.MediaNext, Key.PageDown -> {
                        viewModel.next()
                        overlayToken++
                        true
                    }
                    backward, Key.MediaRewind, Key.MediaPrevious, Key.PageUp -> {
                        viewModel.previous()
                        overlayToken++
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (failed) attempt++ else overlayToken++
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        overlayToken++
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(urls.comicPage(volume.fileId, page))
                // A new attempt is a new request; the cache key stays the page's.
                .setParameter("attempt", attempt, memoryCacheKey = null)
                .build(),
            contentDescription = stringResource(TvR.string.comic_page_of, page + 1, volume.count),
            contentScale = ContentScale.Fit,
            onState = { failed = it is AsyncImagePainter.State.Error },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(TvR.string.comic_page_failed), style = Antresol.typography.body, color = AntresolColors.LabelSecondary)
                Text(stringResource(TvR.string.comic_retry_hint), style = Antresol.typography.caption)
            }
        }
        AnimatedVisibility(
            visible = overlay && !viewModel.atEnd,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PageOverlay(volume, page)
        }
        if (viewModel.atEnd) {
            EndOfVolume(volume, onNext = { onOpenVolume(volume.detail.nextId) }, onExit = onExit)
        }
    }
}

@Composable
private fun PageOverlay(volume: ComicVolume, page: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(volume.detail.card.title, volume.detail.card.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                style = Antresol.typography.bodyEmphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(TvR.string.comic_page_of, page + 1, volume.count), style = Antresol.typography.caption)
        }
        ProgressBar(fraction = (page + 1).toFloat() / volume.count, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EndOfVolume(volume: ComicVolume, onNext: () -> Unit, onExit: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .background(AntresolColors.Glass, AntresolShapes.sheet)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(stringResource(TvR.string.comic_end), style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (volume.detail.nextId > 0) {
                    PillButton(
                        text = stringResource(TvR.string.comic_next_volume),
                        onClick = onNext,
                        style = PillStyle.Primary,
                        modifier = Modifier.focusRequester(first),
                    )
                    PillButton(text = stringResource(R.string.player_back), onClick = onExit)
                } else {
                    PillButton(
                        text = stringResource(R.string.player_back),
                        onClick = onExit,
                        style = PillStyle.Primary,
                        modifier = Modifier.focusRequester(first),
                    )
                }
            }
        }
    }
}
