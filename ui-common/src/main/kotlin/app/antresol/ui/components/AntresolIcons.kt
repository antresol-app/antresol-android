package app.antresol.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The design's line icons: 24-unit grid, stroke 1.9, round caps and joins. Tint them with the
 * `Icon` composable of the current Material library (the paths are drawn in black).
 */
object AntresolIcons {
    val Search: ImageVector = lineIcon("Search") {
        // Lens
        moveTo(10.5f, 4f)
        arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, 13f)
        arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, -13f)
        close()
        // Handle
        moveTo(15.3f, 15.3f)
        lineTo(20f, 20f)
    }

    val Info: ImageVector = lineIcon("Info") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        close()
        moveTo(12f, 11f)
        lineTo(12f, 16.5f)
        moveTo(12f, 7.6f)
        lineTo(12f, 7.7f)
    }

    val ChevronRight: ImageVector = lineIcon("ChevronRight") {
        moveTo(9.5f, 5.5f)
        lineTo(16f, 12f)
        lineTo(9.5f, 18.5f)
    }

    val Lock: ImageVector = lineIcon("Lock") {
        moveTo(6.5f, 10.5f)
        lineTo(17.5f, 10.5f)
        quadTo(19f, 10.5f, 19f, 12f)
        lineTo(19f, 19f)
        quadTo(19f, 20.5f, 17.5f, 20.5f)
        lineTo(6.5f, 20.5f)
        quadTo(5f, 20.5f, 5f, 19f)
        lineTo(5f, 12f)
        quadTo(5f, 10.5f, 6.5f, 10.5f)
        close()
        moveTo(8f, 10.5f)
        lineTo(8f, 7.5f)
        arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
        lineTo(16f, 10.5f)
    }

    val Server: ImageVector = lineIcon("Server") {
        moveTo(5f, 4.5f)
        lineTo(19f, 4.5f)
        lineTo(19f, 10.5f)
        lineTo(5f, 10.5f)
        close()
        moveTo(5f, 13.5f)
        lineTo(19f, 13.5f)
        lineTo(19f, 19.5f)
        lineTo(5f, 19.5f)
        close()
        moveTo(8f, 7.5f)
        lineTo(8.1f, 7.5f)
        moveTo(8f, 16.5f)
        lineTo(8.1f, 16.5f)
    }

    val SignOut: ImageVector = lineIcon("SignOut") {
        moveTo(14f, 4.5f)
        lineTo(6.5f, 4.5f)
        quadTo(5f, 4.5f, 5f, 6f)
        lineTo(5f, 18f)
        quadTo(5f, 19.5f, 6.5f, 19.5f)
        lineTo(14f, 19.5f)
        moveTo(10f, 12f)
        lineTo(20f, 12f)
        moveTo(16.5f, 8.5f)
        lineTo(20f, 12f)
        lineTo(16.5f, 15.5f)
    }

    val SwitchProfile: ImageVector = lineIcon("SwitchProfile") {
        moveTo(12f, 4f)
        arcToRelative(3.8f, 3.8f, 0f, true, true, 0f, 7.6f)
        arcToRelative(3.8f, 3.8f, 0f, true, true, 0f, -7.6f)
        close()
        moveTo(4.8f, 20f)
        quadTo(5.5f, 14.5f, 12f, 14.5f)
        quadTo(18.5f, 14.5f, 19.2f, 20f)
    }

    /** Filled play triangle with rounded corners (the white "Play" pill). */
    val Play: ImageVector = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.5f, 5.2f)
            lineTo(18.5f, 12f)
            lineTo(7.5f, 18.8f)
            close()
        }
    }.build()

    /** Two filled bars with rounded corners, the counterpart of [Play]. */
    val Pause: ImageVector = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 5.5f)
            lineTo(9.8f, 5.5f)
            lineTo(9.8f, 18.5f)
            lineTo(7f, 18.5f)
            close()
            moveTo(14.2f, 5.5f)
            lineTo(17f, 5.5f)
            lineTo(17f, 18.5f)
            lineTo(14.2f, 18.5f)
            close()
        }
    }.build()

    val Plus: ImageVector = lineIcon("Plus") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }

    val Check: ImageVector = lineIcon("Check") {
        moveTo(5f, 12.5f)
        lineTo(10f, 17.5f)
        lineTo(19f, 7f)
    }

    /** A circle with a check mark: "watched". */
    val CheckCircle: ImageVector = lineIcon("CheckCircle") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        close()
        moveTo(8f, 12.3f)
        lineTo(10.8f, 15f)
        lineTo(16f, 9.3f)
    }

    /** Speech bubble: audio track. */
    val Audio: ImageVector = lineIcon("Audio") {
        moveTo(5.5f, 5f)
        lineTo(18.5f, 5f)
        quadTo(20f, 5f, 20f, 6.5f)
        lineTo(20f, 14.5f)
        quadTo(20f, 16f, 18.5f, 16f)
        lineTo(10f, 16f)
        lineTo(6f, 19.5f)
        lineTo(6f, 16f)
        lineTo(5.5f, 16f)
        quadTo(4f, 16f, 4f, 14.5f)
        lineTo(4f, 6.5f)
        quadTo(4f, 5f, 5.5f, 5f)
        close()
    }

    /** A screen with two caption lines: subtitles. */
    val Subtitles: ImageVector = lineIcon("Subtitles") {
        moveTo(5f, 5f)
        lineTo(19f, 5f)
        quadTo(21f, 5f, 21f, 7f)
        lineTo(21f, 17f)
        quadTo(21f, 19f, 19f, 19f)
        lineTo(5f, 19f)
        quadTo(3f, 19f, 3f, 17f)
        lineTo(3f, 7f)
        quadTo(3f, 5f, 5f, 5f)
        close()
        moveTo(6.5f, 12.5f)
        lineTo(10.5f, 12.5f)
        moveTo(13f, 12.5f)
        lineTo(17.5f, 12.5f)
        moveTo(6.5f, 15.5f)
        lineTo(14f, 15.5f)
    }

    /** Three sliders: quality settings. */
    val Sliders: ImageVector = lineIcon("Sliders") {
        moveTo(4f, 7f)
        lineTo(20f, 7f)
        moveTo(4f, 12f)
        lineTo(20f, 12f)
        moveTo(4f, 17f)
        lineTo(20f, 17f)
        moveTo(9f, 5f)
        lineTo(9f, 9f)
        moveTo(15f, 10f)
        lineTo(15f, 14f)
        moveTo(7f, 15f)
        lineTo(7f, 19f)
    }

    /** Play-and-bar: next episode. */
    val SkipNext: ImageVector = lineIcon("SkipNext") {
        moveTo(6f, 6f)
        lineTo(15f, 12f)
        lineTo(6f, 18f)
        close()
        moveTo(18f, 6f)
        lineTo(18f, 18f)
    }

    /** Circular arrow: play from the beginning. */
    val Restart: ImageVector = lineIcon("Restart") {
        moveTo(5.6f, 9f)
        arcTo(7.5f, 7.5f, 0f, true, true, 4.5f, 12.5f)
        moveTo(5.2f, 4.8f)
        lineTo(5.6f, 9f)
        lineTo(9.8f, 8.6f)
    }

    private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
