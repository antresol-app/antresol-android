package app.antresol.mobile.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.ui.theme.Antresol
import kotlinx.coroutines.launch

/**
 * PDF reader: pages in one vertical scroll, rendered on demand at screen width; pinch or double
 * tap to zoom, tap for the title bar and page slider.
 */
@Composable
fun PdfReaderScreen(viewModel: PdfReaderViewModel, onBack: () -> Unit) {
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
            is LoadState.Ready -> PdfPages(state.value, viewModel, onBack)
        }
    }
}

@Composable
private fun PdfPages(book: PdfBook, viewModel: PdfReaderViewModel, onBack: () -> Unit) {
    val document = book.document
    val count = document.pageCount
    val list = rememberLazyListState(initialFirstVisibleItemIndex = book.startPage)
    val zoom = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    var chrome by rememberSaveable { mutableStateOf(false) }
    ImmersiveMode(!chrome)
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { viewModel.onPage(it, count) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.coerceAtMost(900.dp).roundToPx() }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            userScrollEnabled = !zoom.isZoomed,
            modifier = Modifier
                .fillMaxSize()
                .zoomable(zoom) { _, _ -> chrome = !chrome }
                .testTag("pdf-pages"),
        ) {
            items(count) { index -> PdfPage(document, index, widthPx) }
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
                Text(book.title, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                Text(stringResource(R.string.reader_page_of, list.firstVisibleItemIndex + 1, count), style = Antresol.typography.caption)
                if (count > 1) PageSlider(list.firstVisibleItemIndex, count, rtl = false) { scope.launch { list.scrollToItem(it) } }
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(null, document, index, widthPx) { value = document.render(index, widthPx) }
    Box(
        Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .aspectRatio(document.aspectRatios[index].coerceIn(0.2f, 5f))
            .background(Color.White),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}
