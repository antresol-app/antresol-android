package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import app.antresol.core.model.ChannelProgram
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage

/** A programme's picture: its 16:9 backdrop, else its poster, else nothing (the placeholder). */
fun programmeArt(program: ChannelProgram?): String? =
    program?.let { it.backdrop.ifEmpty { it.poster } }?.takeIf { it.isNotEmpty() }

/**
 * The picture of a channel: the still of the programme on air ([url], resolved by the caller), or a
 * neutral system-fill placeholder with a television glyph — never a colourful tile (spec «Look»).
 * The glyph stays until the image has loaded, so a failed image keeps the placeholder.
 */
@Composable
fun ProgrammeStill(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = AntresolShapes.smallCard,
    dimmed: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var loaded by remember(url) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(AntresolColors.BackgroundElevated),
    ) {
        if (!loaded) {
            Icon(
                AntresolIcons.Tv,
                contentDescription = null,
                tint = AntresolColors.LabelTertiary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(maxHeight * 0.34f),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                alpha = if (dimmed) 0.55f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay()
    }
}
