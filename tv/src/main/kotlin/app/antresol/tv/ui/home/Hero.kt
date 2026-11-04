package app.antresol.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.kindLabel
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import coil.compose.AsyncImage
import kotlin.math.abs

/**
 * The full-bleed hero background: backdrop artwork (if any) fading to black, washed with a glow
 * in the artwork's own colour, like the web app's tinted hero. Without artwork the glow alone
 * carries the colour.
 */
@Composable
fun HeroBackground(card: Card?, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    Crossfade(
        targetState = card,
        animationSpec = tween(AntresolMotion.SLOW_MS, easing = AntresolMotion.easeOut),
        label = "hero-background",
        modifier = modifier.fillMaxSize(),
    ) { current ->
        if (current == null) return@Crossfade
        val backdrop = urls?.image(current.backdrop)
        val tintSource = backdrop ?: urls?.image(current.poster)
        val target by rememberArtworkTint(tintSource, fallback = placeholderTint(current.title))
        val tint by animateColorAsState(target, tween(AntresolMotion.SLOW_MS), label = "hero-tint")
        Box(Modifier.fillMaxSize()) {
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.9f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                0f to tint.copy(alpha = if (backdrop != null) 0.45f else 0.95f),
                                0.55f to tint.copy(alpha = if (backdrop != null) 0.2f else 0.45f),
                                1f to Color.Transparent,
                                center = Offset(size.width * 0.68f, size.height * 0.12f),
                                radius = size.width * 0.75f,
                            ),
                        )
                    },
            )
            Box(Modifier.fillMaxSize().background(AntresolGradients.heroScrim))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to AntresolColors.Background.copy(alpha = 0.25f),
                            0.78f to AntresolColors.Background,
                        ),
                    ),
            )
        }
    }
}

/** Eyebrow, huge title, meta line and the Play / Details pills of the hero card. */
@Composable
fun HeroInfo(
    card: Card,
    heroSource: String?,
    count: Int,
    index: Int,
    playButton: @Composable () -> Unit,
    detailsButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = Antresol.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Crossfade(targetState = card, animationSpec = tween(AntresolMotion.MEDIUM_MS), label = "hero-text") { current ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val source = heroSource ?: stringResource(R.string.hero_new_in_library)
                val eyebrow = listOfNotNull(source, kindLabel(current.kind)).joinToString(" · ").uppercase()
                Text(eyebrow, style = typography.eyebrow)
                Text(
                    text = current.title,
                    style = typography.heroTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val meta = listOfNotNull(current.year?.toString(), current.subtitle.ifBlank { null }).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = typography.body, color = AntresolColors.LabelSecondary)
                    BadgeRow(current.badges)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .trapHorizontalFocus()
                .focusGroup(),
        ) {
            playButton()
            detailsButton()
            if (count > 1) {
                Spacer(Modifier.size(8.dp))
                HeroDots(count, index)
            }
        }
    }
}

@Composable
fun HeroPlayButton(card: Card, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        text = stringResource(if (card.progress > 0.0) R.string.action_resume else R.string.action_play),
        onClick = onClick,
        icon = AntresolIcons.Play,
        style = PillStyle.Primary,
        modifier = modifier,
    )
}

@Composable
fun HeroDetailsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        text = stringResource(R.string.action_details),
        onClick = onClick,
        icon = AntresolIcons.Info,
        style = PillStyle.Glass,
        modifier = modifier,
    )
}

@Composable
private fun HeroDots(count: Int, index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (i == index) AntresolColors.Label else AntresolColors.FillPrimary, CircleShape),
            )
        }
    }
}

/** A stable colour for artwork-less cards, from the same presets as the title placeholders. */
private fun placeholderTint(seed: String): Color {
    val ids = AntresolGradients.avatarIds
    return AntresolGradients.avatarColors(ids[abs(seed.hashCode() % ids.size)]).first
}
