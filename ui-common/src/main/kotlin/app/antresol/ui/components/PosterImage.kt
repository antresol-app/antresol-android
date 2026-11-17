package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.ui.image.AntresolImageLoader
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Artwork with the Antresol fallback: the title on a gradient (like the web app's title overlay) is
 * drawn under the image and stays there until the image has loaded, so a crossfade never fades in
 * from an empty box and a failed or missing image ([url] null) keeps the placeholder. Boxes narrower
 * than [COMPACT_ARTWORK] (list thumbnails, the mini player) show the title's initial instead of an
 * ellipsised title. Load [url] with the image loader from
 * [app.antresol.ui.image.AntresolImageLoader], which adds the Bearer header.
 */
@Composable
fun ArtworkImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = AntresolShapes.card,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * Shown when [url] fails (an episode's frame the server is busy making answers 503). The next
     * composition of this artwork (a later visit) asks for [url] again; failures are not cached.
     */
    fallbackUrl: String? = null,
    /**
     * The artwork has about the box's shape (a poster in a poster tile), so the server's copy only has
     * to be the box's larger side. False for a picture that may be much wider than the box (an
     * episode's still in a poster tile, `MediaUrls.artworkFitsTile`): it is asked for at its cut width.
     */
    fitsBox: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var failed by remember(url) { mutableStateOf(false) }
    val model = if (failed && fallbackUrl != null) fallbackUrl else url
    var loaded by remember(model) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(AntresolColors.BackgroundElevated)
            .background(AntresolGradients.placeholder(title)),
    ) {
        if (!loaded) PlaceholderTitle(title, Modifier.matchParentSize())
        if (model != null) {
            val context = LocalContext.current
            // Tile artwork sits in a box of about its own shape, so the server's copy only has to be the box's larger side.
            val request = remember(model, context, fitsBox) {
                ImageRequest.Builder(context).data(model).setParameter(AntresolImageLoader.FITS_BOX, fitsBox, memoryCacheKey = null).build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                onError = {
                    loaded = false
                    if (model == url) failed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}

/**
 * The placeholder's text: the title's initial in the middle of a box narrower than
 * [COMPACT_ARTWORK], else the title at the bottom. Which one is decided when the box is measured,
 * and only that one is measured: no subcomposition (`BoxWithConstraints`) in every tile.
 */
@Composable
private fun PlaceholderTitle(title: String, modifier: Modifier) {
    Layout(
        content = {
            Text(text = title.trim().take(1).uppercase(), style = Antresol.typography.title)
            Text(
                text = title,
                style = Antresol.typography.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(12.dp),
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val compact = constraints.maxWidth < COMPACT_ARTWORK.roundToPx()
        val text = measurables[if (compact) 0 else 1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (compact) {
                text.place((constraints.maxWidth - text.width) / 2, (constraints.maxHeight - text.height) / 2)
            } else {
                text.placeRelative(0, constraints.maxHeight - text.height)
            }
        }
    }
}

/** Artwork narrower than this shows the title's initial rather than the title. */
private val COMPACT_ARTWORK = 96.dp

/** A thin white progress capsule on a translucent track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(AntresolShapes.pill)
            .background(AntresolColors.FillPrimary),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(AntresolShapes.pill)
                .background(AntresolColors.Label),
        )
    }
}
