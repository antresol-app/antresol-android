package app.antresol.mobile.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
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
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.kindLabel
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

private const val ROTATE_EVERY_MS = 8_000L

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
    val height: Dp = if (tablet) min(screenHeight * 0.66f, 620.dp) else min(width * 1.42f, screenHeight * 0.74f)
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
            HeroPage(cards[page], heroSource, tablet, onPlay, onDetails, bottomInset = if (cards.size > 1) 26.dp else 8.dp)
        }
        if (cards.size > 1) {
            Row(
                modifier = Modifier
                    .align(if (tablet) Alignment.BottomStart else Alignment.BottomCenter)
                    .padding(horizontal = if (tablet) 32.dp else 0.dp, vertical = 8.dp),
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
    onPlay: (Card) -> Unit,
    onDetails: (Card) -> Unit,
    bottomInset: Dp,
) {
    val urls = LocalMediaUrls.current
    val artwork = urls?.image(card.backdrop.ifBlank { card.poster })
    val tint by rememberArtworkTint(urls?.image(card.poster.ifBlank { card.backdrop }), AntresolColors.BrandStart)

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
                    .alpha(0.9f),
            )
        }
        Box(Modifier.fillMaxSize().background(AntresolGradients.heroFade))
        if (tablet) Box(Modifier.fillMaxSize().background(AntresolGradients.heroScrim))

        val eyebrow = buildList {
            add(heroSource ?: stringResource(R.string.hero_new_in_library))
            kindLabel(card.kind)?.let(::add)
        }.joinToString(" · ").uppercase(Locale.getDefault())
        val alignment = if (tablet) Alignment.Start else Alignment.CenterHorizontally
        val textAlign = if (tablet) TextAlign.Start else TextAlign.Center
        Column(
            modifier = Modifier
                .align(if (tablet) Alignment.BottomStart else Alignment.BottomCenter)
                .padding(start = if (tablet) 32.dp else 16.dp, end = 16.dp, bottom = bottomInset)
                .then(if (tablet) Modifier.widthIn(max = 520.dp) else Modifier.fillMaxWidth()),
            horizontalAlignment = alignment,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(eyebrow, style = Antresol.typography.eyebrow, textAlign = textAlign)
            Text(
                text = card.title,
                style = Antresol.typography.heroTitle,
                textAlign = textAlign,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(card.year?.toString(), card.subtitle.ifBlank { null }).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = Antresol.typography.caption, textAlign = textAlign)
            }
            if (tablet) BadgeRow(card.badges)
            Row(
                modifier = Modifier.padding(top = 8.dp).then(if (tablet) Modifier else Modifier.fillMaxWidth()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val buttonModifier = if (tablet) Modifier else Modifier.weight(1f)
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
    }
}
