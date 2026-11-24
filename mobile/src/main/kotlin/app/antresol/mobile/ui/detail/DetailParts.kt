package app.antresol.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.OpenedPoster
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.posterBounds
import app.antresol.mobile.ui.components.PageSideInsets
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.components.fullBleed
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The top of a detail page: an artwork-tinted glow fading to black behind [artwork], the back
 * button over the status bar (with optional [actions] opposite it), and the title block at the bottom.
 */
@Composable
fun DetailHeader(
    tintUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    artwork: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint by rememberArtworkTint(tintUrl, AntresolColors.BrandStart)
    // The tint and the artwork go edge to edge; the bar and the content keep clear of a cutout at the side.
    val sides = PageSideInsets
    Box(modifier.fullBleed(sides).fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to tint, 0.7f to tint.copy(alpha = 0.25f), 1f to AntresolColors.Background)),
        )
        Box(Modifier.matchParentSize(), content = artwork)
        Column(Modifier.fillMaxWidth().windowInsetsPadding(sides)) {
            BackBar(onBack, actions = actions)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageGutter)
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** A long description that expands on tap. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 4) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, role = null) { expanded = !expanded }
            .padding(horizontal = PageGutter, vertical = 8.dp),
    )
}

/** Label + value lines ("Audio  English · AAC · Stereo"). */
@Composable
fun InfoLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary))
        Text(value, style = Antresol.typography.body)
    }
}

/** Shows [notice] (a string resource: a failed write, a playback request that failed) once as a snackbar. */
@Composable
fun NoticeSnackbar(notice: Int?, host: SnackbarHostState, onShown: () -> Unit) {
    val resources = LocalContext.current.resources
    // Shown from the page's scope, not from the effect: [onShown] clears the notice, which restarts
    // the effect and would take a snackbar shown inside it down again at once.
    val scope = rememberCoroutineScope()
    val snackbars = remember(scope, host) { NoticeSnackbars(scope, host) }
    LaunchedEffect(notice) {
        if (notice != null) {
            onShown()
            snackbars.show(resources.getString(notice))
        }
    }
}

/**
 * The snackbars of a page's notices on [host], shown from [scope]. A message that is already
 * showing or waiting is not queued a second time (five taps on a toggle while offline are one
 * sentence, not the same one five times in a row): it is shown anew in its place.
 */
class NoticeSnackbars(private val scope: CoroutineScope, private val host: SnackbarHostState) {
    private val shown = HashMap<String, Job>()

    fun show(message: String) {
        // Cancelling takes the snackbar down (or out of the queue).
        shown.remove(message)?.cancel()
        shown[message] = scope.launch {
            val self = coroutineContext[Job]
            try {
                host.showSnackbar(message)
            } finally {
                if (shown[message] === self) shown.remove(message)
            }
        }
    }
}

/** A full-size centred box for loading and error states under the back button; [art] is drawn behind them. */
@Composable
fun DetailStateBox(onBack: () -> Unit, art: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        art?.invoke()
        Column(Modifier.fillMaxSize().windowInsetsPadding(PageSideInsets)) {
            BackBar(onBack)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** The height of a video page's artwork (the page draws it 60 dp taller, under the title block). */
fun videoArtHeight(configuration: Configuration): Dp = min(configuration.screenWidthDp.dp * 0.9f, configuration.screenHeightDp.dp * 0.5f)

/**
 * The tile's picture in the place of the page's artwork, while the page loads: the zoom out of the tile
 * lands on it, and the artwork takes over the same shared key once it has loaded.
 */
@Composable
fun PosterLoadingArt(poster: OpenedPoster) {
    val artHeight = videoArtHeight(LocalConfiguration.current)
    Box(Modifier.fillMaxWidth().height(artHeight + 60.dp)) {
        ArtworkImage(
            url = poster.imageUrl,
            title = "",
            shape = RectangleShape,
            modifier = Modifier.posterBounds(poster.key, AntresolShapes.card).fillMaxSize().alpha(0.85f),
        )
        Box(Modifier.matchParentSize().background(AntresolGradients.heroFade))
    }
}
