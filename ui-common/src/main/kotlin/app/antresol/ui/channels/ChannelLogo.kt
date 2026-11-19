package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.antresol.ui.theme.AntresolGradients
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.image.AntresolImageLoader
import app.antresol.ui.theme.AntresolShapes
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** A logo box is 3:2, the shape most channel logos are drawn for. */
const val CHANNEL_LOGO_ASPECT = 3f / 2f

/**
 * True inside a list that keeps a logo column (the guide's column, the mini-guide: where some
 * channel has a logo; the channel lists' rows always do): a channel
 * without one then shows its initials (or, without a name, keeps an empty slot of the logo's size),
 * so the names line up down the list, as the web does. Single-channel places (banner, preview)
 * leave it false.
 */
val LocalReserveChannelLogo = compositionLocalOf { false }

/** Whether any of [logoUrls] is set: the list should reserve the logo slot ([LocalReserveChannelLogo]). */
fun reserveLogoSlot(logoUrls: List<String?>): Boolean = logoUrls.any { !it.isNullOrEmpty() }

/**
 * The pictures a channel's logo box tries, in order: its own logo ([url]: uploaded, found by the
 * server, or a playlist's), then [alt] (an external channel's guide icon, `logoAlt`). Blank and
 * repeated ones are left out.
 */
fun logoSources(url: String?, alt: String?): List<String> = listOfNotNull(url, alt).filter { it.isNotBlank() }.distinct()

/** Words a playlist adds to a channel's name that say nothing of it (as the web's `channelInitials`). */
private val InitialsNoise = Regex("^(hd|fhd|uhd|sd|hq|4k|8k|orig|original|\\+\\d+|\\d+p|50fps|h\\.?26[45]|hevc)$", RegexOption.IGNORE_CASE)
private val InitialsSplit = Regex("[\\s\\-–—_|/\\\\:.,·•()\\[\\]{}«»\"'#&!?*]+")

/**
 * A channel's initials for a box without a logo: the first letters of its first two words (one word:
 * its first letter), quality marks («HD», «+2», «1080p») left out; mirrors the web's `channelInitials`.
 */
fun channelInitials(name: String): String {
    val words = name.split(InitialsSplit).filter { it.isNotEmpty() && !InitialsNoise.matches(it) }
    val out = words.take(2).mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }.joinToString("")
    return out.ifEmpty { name.trim().take(1).ifEmpty { "?" } }.uppercase()
}

/**
 * The colour of a channel's generated tile: one of the avatar gradients `grad-1`…`grad-7` (never
 * the grey `grad-8`), picked by a hash of the name exactly as the web's `channelTone`: the trimmed,
 * lower-cased name's Unicode code points, `h = (h * 31 + cp) >>> 0`, then `grad-(h % 7 + 1)`.
 */
fun channelTileGradient(name: String): String {
    var h = 0L
    val text = name.trim().lowercase()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        h = (h * 31 + cp) and 0xFFFFFFFFL
        i += Character.charCount(cp)
    }
    return "grad-${(h % 7).toInt() + 1}"
}

/**
 * A channel's identity, the same in every list, card, banner and player: its logo ([url], resolved
 * by the caller from `logoUrl`), else [altUrl] (the guide icon, `logoAlt`), drawn as it is in a 3:2
 * slot of [height] — no box behind it; without either (or when neither loads) a generated tile:
 * the channel's colour ([channelTileGradient]) with its initials in white. Without a [name] nothing
 * is drawn, or, where a list keeps the column ([reserve]), an empty slot of the same size.
 */
@Composable
fun ChannelLogo(
    url: String?,
    height: Dp,
    modifier: Modifier = Modifier,
    reserve: Boolean = LocalReserveChannelLogo.current,
    altUrl: String? = null,
    name: String? = null,
) {
    val sources = remember(url, altUrl) { logoSources(url, altUrl) }
    var failed by remember(sources) { mutableIntStateOf(0) }
    val current = sources.getOrNull(failed)
    if (current == null) {
        when {
            !name.isNullOrBlank() -> ChannelTile(name, height, modifier)
            reserve -> Spacer(modifier.size(width = height * CHANNEL_LOGO_ASPECT, height = height).testTag("channel-logo-slot"))
            else -> Unit
        }
        return
    }
    Box(modifier.size(width = height * CHANNEL_LOGO_ASPECT, height = height).testTag("channel-logo")) {
        val context = LocalContext.current
        // A logo is drawn as it is, never as the server's smaller JPEG copy (it would get a background).
        val request = remember(current, context) {
            ImageRequest.Builder(context).data(current).setParameter(AntresolImageLoader.TRANSPARENT, true, memoryCacheKey = null).build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { failed++ },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The generated tile of a channel without a logo: its colour and its initials in white. */
@Composable
fun ChannelTile(name: String, height: Dp, modifier: Modifier = Modifier) {
    val gradient = remember(name) { AntresolGradients.avatar(channelTileGradient(name)) }
    Box(
        modifier
            .size(width = height * CHANNEL_LOGO_ASPECT, height = height)
            .clip(if (height >= 32.dp) AntresolShapes.badge else LogoShapeSmall)
            .background(gradient)
            .testTag("channel-initials"),
    ) {
        BasicText(
            channelInitials(name),
            maxLines = 1,
            style = TextStyle(
                color = Color.White,
                fontSize = with(LocalDensity.current) { (height * 0.44f).toSp() },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

private val LogoShapeSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
