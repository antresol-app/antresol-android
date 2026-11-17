package app.antresol.mobile.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.antresol.core.model.Card
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.ui.components.kindLabel
import app.antresol.mobile.ui.components.PageSideInsets
import app.antresol.mobile.ui.shell.BottomBarSpace
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.delay

private const val ROTATE_EVERY_MS = 8_000L

/** Screens lower than this (a phone on its side) give the hero the whole height. */
private const val SHORT_SCREEN_DP = 480

/** Room the tab header takes below the status bar: 38 dp avatar + 8 dp above and below. */
private val HEADER_CLEARANCE = 54.dp

/**
 * The home hero: full-bleed artwork tinted with its own colour and fading to black, the eyebrow
 * ("NEW IN LIBRARY · MOVIE"), a huge title, the year and badges, and the white Play + glass Details
 * pills. Several hero cards rotate (swipe or every 8 s). Phones centre the text; tablets align it left.
 */
@Composable
fun Hero(
    cards: List<Card>,
    heroSource: String?,
    onPlay: (Card) -> Unit,
    onDetails: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val tablet = configuration.screenWidthDp >= 600
    // A phone on its side: the hero fills the screen between the floating header and the tab bar.
    val short = configuration.screenHeightDp < SHORT_SCREEN_DP
    val height: Dp = when {
        // The bar floats above the system's navigation area: the buttons end clear of it, not at its edge.
        short -> screenHeight - BottomBarSpace - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        tablet -> min(screenHeight * 0.66f, 620.dp)
        else -> min(width * 1.42f, screenHeight * 0.74f)
    }
    val pager = rememberPagerState { cards.size }

    if (cards.size > 1) {
        LaunchedEffect(pager) {
            while (true) {
                delay(ROTATE_EVERY_MS)
                if (!pager.isScrollInProgress) {
                    pager.animateScrollToPage((pager.currentPage + 1) % cards.size, animationSpec = tween(600))
                }
            }
        }
    }

    Box(modifier.fillMaxWidth().height(height)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { cards[it].id }) { page ->
            HeroPage(cards[page], heroSource, tablet, short, onPlay, onDetails, bottomInset = if (cards.size > 1) 26.dp else 8.dp)
        }
        if (cards.size > 1) {
            Row(
                modifier = Modifier
                    .align(if (tablet) Alignment.BottomStart else Alignment.BottomCenter)
                    .windowInsetsPadding(PageSideInsets)
                    .padding(horizontal = if (short) PageGutter else if (tablet) 32.dp else 0.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(cards.size) { index ->
                    Box(
                        Modifier
                            .size(if (index == pager.currentPage) 8.dp else 6.dp)
                            .background(
                                if (index == pager.currentPage) AntresolColors.Label else AntresolColors.FillPrimary,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(
    card: Card,
    heroSource: String?,
    tablet: Boolean,
    short: Boolean,
    onPlay: (Card) -> Unit,
    onDetails: (Card) -> Unit,
    bottomInset: Dp,
) {
    val urls = LocalMediaUrls.current
    val artwork = urls?.image(card.backdrop.ifBlank { card.poster })
    // The glow below comes from the image that is drawn, so the two always match.
    val tint by rememberArtworkTint(artwork, AntresolColors.BrandStart)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to tint, 0.75f to tint.copy(alpha = 0.35f), 1f to AntresolColors.Background)),
        )
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.78f)
                    .alpha(0.9f)
                    // The artwork fades out on its own, so it has no hard bottom edge behind the title.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
            )
        }
        Box(Modifier.fillMaxSize().background(AntresolGradients.heroFade))
        if (tablet) Box(Modifier.fillMaxSize().background(AntresolGradients.heroScrim))

        val eyebrow = buildList {
            add(heroSource ?: stringResource(R.string.hero_new_in_library))
            kindLabel(card.kind)?.let(::add)
        }.joinToString(" · ").uppercase(Locale.getDefault())
        val alignment = if (tablet || short) Alignment.Start else Alignment.CenterHorizontally
        val textAlign = if (tablet || short) TextAlign.Start else TextAlign.Center
        val text: @Composable () -> Unit = {
            Text(eyebrow, style = Antresol.typography.eyebrow, textAlign = textAlign)
            Text(
                text = card.title,
                style = Antresol.typography.heroTitle,
                textAlign = textAlign,
                maxLines = if (short) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(card.year?.toString(), card.subtitle.ifBlank { null }).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = Antresol.typography.caption, textAlign = textAlign)
            }
        }
        val buttons: @Composable (Modifier, Boolean) -> Unit = { rowModifier, stretch ->
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val buttonModifier = if (stretch) Modifier.weight(1f) else Modifier
                PrimaryPillButton(
                    text = stringResource(if (card.progress > 0.0) R.string.action_resume else R.string.action_play),
                    icon = AntresolIcons.Play,
                    onClick = { onPlay(card) },
                    modifier = buttonModifier,
                )
                GlassPillButton(
                    text = stringResource(R.string.action_details),
                    icon = AntresolIcons.Info,
                    onClick = { onDetails(card) },
                    modifier = buttonModifier,
                )
            }
        }
        // Never under the tab header that floats over the hero, nor under a cutout at the side (the backdrop is).
        val clearHeader = Modifier
            .windowInsetsPadding(PageSideInsets)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = HEADER_CLEARANCE)
        if (short) {
            // A phone on its side: the text on the left, the buttons beside it, all in one band.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(clearHeader)
                    // Lined up with the header's logo and avatar.
                    .padding(start = PageGutter, end = PageGutter, bottom = bottomInset)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { text() }
                buttons(Modifier, false)
            }
        } else {
            Column(
                modifier = Modifier
                    .align(if (tablet) Alignment.BottomStart else Alignment.BottomCenter)
                    .then(clearHeader)
                    .padding(start = if (tablet) 32.dp else 16.dp, end = 16.dp, bottom = bottomInset)
                    .then(if (tablet) Modifier.widthIn(max = 520.dp) else Modifier.fillMaxWidth()),
                horizontalAlignment = alignment,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                text()
                if (tablet) BadgeRow(card.badges)
                buttons(Modifier.padding(top = 8.dp).then(if (tablet) Modifier else Modifier.fillMaxWidth()), !tablet)
            }
        }
    }
}
