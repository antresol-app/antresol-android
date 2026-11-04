package app.antresol.mobile.reader

import android.app.Activity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Pinch/double-tap zoom of one page. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    internal fun apply(newScale: Float, newOffset: Offset, size: IntSize) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        // Keep the page covering the viewport: at most (scale - 1) / 2 of it may slide out per side.
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        offset = if (scale <= MIN_SCALE) Offset.Zero else Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * Pinch to zoom, pan while zoomed, double tap to zoom at the finger or back out, single tap
 * reported to [onTap]. One-finger drags are left alone while not zoomed, so a pager can swipe.
 */
fun Modifier.zoomable(state: ZoomState, onTap: (Offset, IntSize) -> Unit): Modifier = this
    .clipToBounds()
    .pointerInput(state) {
        detectTapGestures(
            onTap = { onTap(it, size) },
            onDoubleTap = { tap ->
                if (state.isZoomed) {
                    state.reset()
                } else {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val scale = ZoomState.DOUBLE_TAP_SCALE
                    state.apply(scale, (tap - center) * (1f - scale), size)
                }
            },
        )
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers > 1 || state.isZoomed) {
                    // Zoom around the fingers: the point under the centroid stays under it.
                    val centroid = event.calculateCentroid(useCurrent = false) - Offset(size.width / 2f, size.height / 2f)
                    val target = (state.scale * event.calculateZoom()).coerceIn(ZoomState.MIN_SCALE, ZoomState.MAX_SCALE)
                    val zoom = target / state.scale
                    state.apply(target, (state.offset - centroid) * zoom + centroid + event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

/** Hides the status and navigation bars while [immersive]; restores them when the reader closes. */
@Composable
fun ImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
