package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.compositionLocalOf
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
 * True inside a list where some channel has a logo (rows, the guide's column, the mini-guide): a
 * channel without one then keeps an empty slot of the logo's size, so the names line up down the
 * list, as the web does. Single-channel places (banner, preview) leave it false.
 */
val LocalReserveChannelLogo = compositionLocalOf { false }

/** Whether any of [logoUrls] is set: the list should reserve the logo slot ([LocalReserveChannelLogo]). */
fun reserveLogoSlot(logoUrls: List<String?>): Boolean = logoUrls.any { !it.isNullOrEmpty() }

/**
 * A channel's uploaded logo ([url], resolved by the caller from `logoUrl`), small, fitted inside a
 * neutral rounded box of [height] (3:2). Without a logo, or when it cannot be loaded, nothing is
 * drawn (the channel shows its number and name only, never a generated tile), or just an empty slot
 * of the same size where the list reserves one ([LocalReserveChannelLogo]).
 */
@Composable
fun ChannelLogo(url: String?, height: Dp, modifier: Modifier = Modifier, reserve: Boolean = LocalReserveChannelLogo.current) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) {
        if (reserve) Spacer(modifier.size(width = height * CHANNEL_LOGO_ASPECT, height = height).testTag("channel-logo-slot"))
        return
    }
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
