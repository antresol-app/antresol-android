package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage

/**
 * The neutral box a channel's uploaded logo sits in: an opaque mid-dark grey (iOS system grey 5),
 * the same under every logo, so a logo drawn for a light or a dark background stays readable and
 * no channel gets a colour of its own.
 */
val ChannelLogoBox = Color(0xFF2C2C2E)

/** A logo box is 3:2, the shape most channel logos are drawn for. */
const val CHANNEL_LOGO_ASPECT = 3f / 2f

/**
 * A channel's uploaded logo ([url], resolved by the caller from `logoUrl`), small, fitted inside a
 * neutral rounded box of [height] (3:2). Nothing is drawn without a logo, or when it cannot be
 * loaded: the channel then shows its number and name only (never a generated tile).
 */
@Composable
fun ChannelLogo(url: String?, height: Dp, modifier: Modifier = Modifier) {
    if (url == null) return
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    Box(
        modifier
            .size(width = height * CHANNEL_LOGO_ASPECT, height = height)
            .clip(if (height >= 32.dp) AntresolShapes.badge else LogoShapeSmall)
            .background(ChannelLogoBox)
            .padding(horizontal = height * 0.14f, vertical = height * 0.12f)
            .testTag("channel-logo"),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val LogoShapeSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
