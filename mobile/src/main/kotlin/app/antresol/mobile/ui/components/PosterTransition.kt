@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.antresol.mobile.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import app.antresol.core.model.Card

/** How long a poster takes to open into its page and to shrink back (Apple TV's zoom, a little shorter). */
const val POSTER_ZOOM_MS = 340

/**
 * The tile a detail page was opened from. [key] ties the tile's artwork to the page's: the same
 * `sharedBounds` key on both sides makes the artwork grow from the tile into the page and, on Back,
 * shrink into the tile again. [imageUrl] is the tile's picture, which the page draws while it loads, so
 * the zoom has something to land on.
 */
class OpenedPoster(val key: String, val imageUrl: String?)

/**
 * The shell's shared-transition state: the [SharedTransitionScope] around the navigation host and
 * the poster that was tapped last (a page takes it once, in the first moments after the tap).
 */
class PosterTransitions(val scope: SharedTransitionScope) {
    private var tapped: OpenedPoster? = null
    private var tappedAt = 0L

    /** A tile was tapped to open a page. */
    fun opened(poster: OpenedPoster) {
        tapped = poster
        tappedAt = SystemClock.uptimeMillis()
    }

    /**
     * The tile the page that is opening was tapped from, once. A tap that did not open a page (it played)
     * leaves a stale one: it is dropped after a second, so a later page opened another way does not zoom
     * out of a tile it was not opened from.
     */
    fun take(): OpenedPoster? = tapped?.takeIf { SystemClock.uptimeMillis() - tappedAt < FRESH_MS }.also { tapped = null }

    private companion object {
        const val FRESH_MS = 1_000L
    }
}

/** What a page of the navigation host sees: the shell's [PosterTransitions] and the page's own visibility scope. */
class PosterTransition(val transitions: PosterTransitions, val visibility: AnimatedVisibilityScope)

/** Set by the shell around the navigation host; null where there is no shared transition (tests of a single screen). */
val LocalPosterTransitions = staticCompositionLocalOf<PosterTransitions?> { null }

/**
 * Whether the poster zoom is on (it is, in the app). The zoom draws its artwork through a hardware layer, which a
 * test that draws the view on a software canvas to take a screenshot cannot do: such a test turns it off.
 */
val LocalPosterZoom = staticCompositionLocalOf { true }

/**
 * The host of the shell's navigation: with the zoom on, the [SharedTransitionLayout] the pages share their posters
 * in, and the [PosterTransitions] of it for them.
 */
@Composable
fun PosterZoomHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!LocalPosterZoom.current) {
        content()
        return
    }
    SharedTransitionLayout(modifier) {
        val posters = remember(this) { PosterTransitions(this) }
        CompositionLocalProvider(LocalPosterTransitions provides posters) { content() }
    }
}

/** Set for each page of the navigation host. */
val LocalPosterTransition = compositionLocalOf<PosterTransition?> { null }

/** Which shelf or grid a tile sits in: the same card in two shelves of a page must not share one key. */
val LocalPosterOrigin = compositionLocalOf { "" }

fun posterKey(origin: String, card: Card): String = "poster/$origin/${card.kind}:${card.id}:${card.tmdbId ?: 0}"

/** Gives the page the shell's shared transition with the page's own [AnimatedVisibilityScope] (a destination's content). */
@Composable
fun AnimatedVisibilityScope.ProvidePosterTransition(content: @Composable () -> Unit) {
    val transitions = LocalPosterTransitions.current
    val visibility = this
    CompositionLocalProvider(LocalPosterTransition provides transitions?.let { PosterTransition(it, visibility) }) { content() }
}

private val PosterBounds = BoundsTransform { _, _ -> tween(durationMillis = POSTER_ZOOM_MS, easing = FastOutSlowInEasing) }

/**
 * The artwork that zooms between a tile and its page. Put it before the size modifiers of the artwork.
 * Without a shared transition (no [key], or none around) it changes nothing.
 */
@Composable
fun Modifier.posterBounds(key: String?, shape: Shape): Modifier {
    val transition = LocalPosterTransition.current ?: return this
    if (key == null) return this
    return with(transition.transitions.scope) {
        this@posterBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = transition.visibility,
            boundsTransform = PosterBounds,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Crop, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(shape),
        ).testTag(key)
    }
}

/** The poster the page being composed was opened from, taken once (stable across its recompositions). */
@Composable
fun rememberOpenedPoster(): OpenedPoster? {
    val transitions = LocalPosterTransition.current?.transitions
    return remember { transitions?.take() }
}
