package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.antresol.ui.theme.AntresolColors
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/**
 * The neutral box a channel's uploaded logo sits in: an opaque mid-dark grey (iOS system grey 5),
 * the same under every logo, so a logo drawn for a light or a dark background stays readable and
 * no channel gets a colour of its own.
 */
val ChannelLogoBox = Color(0xFF2C2C2E)

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
 * A channel's logo ([url], resolved by the caller from `logoUrl`), small, fitted inside a neutral
 * rounded box of [height] (3:2). When it is empty or cannot be loaded the box tries [altUrl] (the
 * guide icon, `logoAlt`). Without either, nothing is drawn, except where the list keeps the slot
 * ([reserve]): there [name] shows its initials in the same box (as the web's list rows; without a
 * name an empty slot of the same size). Never a coloured tile and never an empty grey box.
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
            !reserve -> Unit
            !name.isNullOrBlank() -> LogoBox(height, modifier.testTag("channel-initials")) {
                BasicText(
                    channelInitials(name),
                    maxLines = 1,
                    style = TextStyle(
                        color = AntresolColors.LabelSecondary,
                        fontSize = with(LocalDensity.current) { (height * 0.42f).toSp() },
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> Spacer(modifier.size(width = height * CHANNEL_LOGO_ASPECT, height = height).testTag("channel-logo-slot"))
        }
        return
    }
    LogoBox(height, modifier.testTag("channel-logo")) {
        val context = LocalContext.current
        // A logo is drawn on the box above: it is asked for as it is, never as the server's smaller JPEG copy.
        val request = remember(current, context) {
            ImageRequest.Builder(context).data(current).setParameter(AntresolImageLoader.TRANSPARENT, true, memoryCacheKey = null).build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { failed++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = height * 0.14f, vertical = height * 0.12f),
        )
    }
}

@Composable
private fun LogoBox(height: Dp, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(width = height * CHANNEL_LOGO_ASPECT, height = height)
            .clip(if (height >= 32.dp) AntresolShapes.badge else LogoShapeSmall)
            .background(ChannelLogoBox),
        content = content,
    )
}

private val LogoShapeSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
