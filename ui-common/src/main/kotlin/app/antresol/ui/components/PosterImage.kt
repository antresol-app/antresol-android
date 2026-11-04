package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage

/**
 * Artwork with the Antresol fallback: while loading, on error, or when [url] is null the title is
 * drawn on a gradient (like the web app's title overlay). Load [url] with the image loader from
 * [app.antresol.ui.image.AntresolImageLoader], which adds the Bearer header.
 */
@Composable
fun ArtworkImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = AntresolShapes.card,
    contentScale: ContentScale = ContentScale.Crop,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(AntresolColors.BackgroundElevated),
    ) {
        if (!loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AntresolGradients.placeholder(title)),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = title,
                    style = Antresol.typography.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}

/** A 2:3 poster with rounded corners (radius 12) and an optional progress bar along the bottom. */
@Composable
fun PosterImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    progress: Double = 0.0,
) {
    ArtworkImage(
        url = url,
        title = title,
        modifier = modifier.aspectRatio(AntresolMotion.POSTER_ASPECT),
    ) {
        if (progress > 0.0) {
            ProgressBar(
                fraction = progress.toFloat(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

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
