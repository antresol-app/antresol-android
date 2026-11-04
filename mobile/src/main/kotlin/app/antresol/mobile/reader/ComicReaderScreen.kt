package app.antresol.mobile.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Comic/manga reader: one page per screen, swiped in the volume's reading direction (manga
 * right to left), pinch or double tap to zoom, tap the edges to turn pages and the middle for
 * the title bar and page slider. The last page offers the next volume.
 */
@Composable
fun ComicReaderScreen(viewModel: ComicReaderViewModel, onBack: () -> Unit, onOpenVolume: (Long) -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = viewModel.state) {
            LoadState.Loading -> ReaderStateBox(onBack) { LoadingView() }
            is LoadState.Failed -> ReaderStateBox(onBack) {
                if (state.error is NothingToReadException) {
                    Text(stringResource(R.string.reader_unavailable), style = Antresol.typography.body)
                } else {
                    ErrorView(state.error, onRetry = viewModel::reload)
                }
            }
            is LoadState.Ready -> ComicPagesView(state.value, viewModel, onBack, onOpenVolume)
        }
    }
}

@Composable
private fun ComicPagesView(volume: ComicVolume, viewModel: ComicReaderViewModel, onBack: () -> Unit, onOpenVolume: (Long) -> Unit) {
    val urls = LocalMediaUrls.current
    val count = volume.pages.count
    val rtl = volume.pages.isRightToLeft
    val pager = rememberPagerState(initialPage = volume.startPage) { count }
    val scope = rememberCoroutineScope()
    val zoom = remember { mutableStateMapOf<Int, ZoomState>() }
    var chrome by rememberSaveable { mutableStateOf(false) }
    ImmersiveMode(!chrome)

    LaunchedEffect(pager.settledPage) {
        viewModel.onPage(pager.settledPage, count)
        zoom.filterKeys { it != pager.settledPage }.values.forEach { it.reset() }
    }
    val turn: (Int) -> Unit = { delta ->
        val target = (pager.currentPage + delta).coerceIn(0, count - 1)
        scope.launch { pager.animateScrollToPage(target) }
    }

    HorizontalPager(
        state = pager,
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        userScrollEnabled = zoom[pager.currentPage]?.isZoomed != true,
        key = { it },
        modifier = Modifier.fillMaxSize().testTag("comic-pager"),
    ) { index ->
        val state = zoom.getOrPut(index) { ZoomState() }
        var loading by remember { mutableStateOf(true) }
        Box(
            Modifier
                .fillMaxSize()
                .zoomable(state) { tap, size ->
                    val third = size.width / 3f
                    // Edge taps turn the page that lies on that side.
                    val leftIsBack = !rtl
                    when {
                        tap.x < third -> turn(if (leftIsBack) -1 else 1)
                        tap.x > 2 * third -> turn(if (leftIsBack) 1 else -1)
                        else -> chrome = !chrome
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = urls?.comicPage(volume.fileId, index),
                contentDescription = stringResource(R.string.reader_page_of, index + 1, count),
                contentScale = ContentScale.Fit,
                onState = { loading = it is AsyncImagePainter.State.Loading },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) CircularProgressIndicator(color = AntresolColors.LabelSecondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }

    AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIconButton(MobileIcons.ChevronLeft, stringResource(R.string.back), onBack)
                Column(Modifier.weight(1f)) {
                    Text(volume.title, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (volume.subtitle.isNotBlank()) Text(volume.subtitle, style = Antresol.typography.caption, maxLines = 1)
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pager.currentPage == count - 1 && volume.nextId > 0) {
                    PrimaryPillButton(
                        text = stringResource(R.string.reader_next_volume),
                        onClick = { onOpenVolume(volume.nextId) },
                        modifier = Modifier.padding(bottom = 8.dp).testTag("reader-next-volume"),
                    )
                }
                Text(
                    stringResource(R.string.reader_page_of, pager.currentPage + 1, count),
                    style = Antresol.typography.caption,
                    modifier = Modifier.testTag("reader-page"),
                )
                if (count > 1) {
                    PageSlider(pager.currentPage, count, rtl) { scope.launch { pager.scrollToPage(it) } }
                }
            }
        }
    }
}

/** A page slider that runs in the reading direction (right to left for manga). */
@Composable
fun PageSlider(page: Int, count: Int, rtl: Boolean, onPage: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Slider(
            value = dragging ?: page.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onPage(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..(count - 1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AntresolColors.Label,
                activeTrackColor = AntresolColors.Label,
                inactiveTrackColor = AntresolColors.Label.copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ReaderStateBox(onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            GlassIconButton(MobileIcons.ChevronLeft, stringResource(R.string.back), onBack)
        }
        Box(Modifier.align(Alignment.Center).padding(24.dp)) { content() }
    }
}
