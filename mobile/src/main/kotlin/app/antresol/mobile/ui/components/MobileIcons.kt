package app.antresol.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Phone line icons in the design's style (24 grid, stroke 1.9, round caps), complementing
 * [app.antresol.ui.components.AntresolIcons]. Tint them with `Icon`.
 */
object MobileIcons {
    val Home: ImageVector = lineIcon("Home") {
        moveTo(4f, 10.5f)
        lineTo(12f, 4f)
        lineTo(20f, 10.5f)
        lineTo(20f, 18.5f)
        quadTo(20f, 20f, 18.5f, 20f)
        lineTo(14.5f, 20f)
        lineTo(14.5f, 14.5f)
        lineTo(9.5f, 14.5f)
        lineTo(9.5f, 20f)
        lineTo(5.5f, 20f)
        quadTo(4f, 20f, 4f, 18.5f)
        close()
    }

    val Film: ImageVector = lineIcon("Film") {
        roundedRect(3.5f, 4.5f, 20.5f, 19.5f, 2f)
        moveTo(7.5f, 4.5f)
        lineTo(7.5f, 19.5f)
        moveTo(16.5f, 4.5f)
        lineTo(16.5f, 19.5f)
        moveTo(3.5f, 9.5f)
        lineTo(7.5f, 9.5f)
        moveTo(3.5f, 14.5f)
        lineTo(7.5f, 14.5f)
        moveTo(16.5f, 9.5f)
        lineTo(20.5f, 9.5f)
        moveTo(16.5f, 14.5f)
        lineTo(20.5f, 14.5f)
    }

    val Tv: ImageVector = lineIcon("Tv") {
        roundedRect(3f, 5f, 21f, 16.5f, 2f)
        moveTo(8.5f, 20f)
        lineTo(15.5f, 20f)
    }

    val Music: ImageVector = lineIcon("Music") {
        moveTo(9f, 17.5f)
        lineTo(9f, 5.5f)
        lineTo(19f, 3.8f)
        lineTo(19f, 15.8f)
        moveTo(6.6f, 15.1f)
        arcToRelative(2.4f, 2.4f, 0f, true, true, 0f, 4.8f)
        arcToRelative(2.4f, 2.4f, 0f, true, true, 0f, -4.8f)
        close()
        moveTo(16.6f, 13.4f)
        arcToRelative(2.4f, 2.4f, 0f, true, true, 0f, 4.8f)
        arcToRelative(2.4f, 2.4f, 0f, true, true, 0f, -4.8f)
        close()
    }

    val Book: ImageVector = lineIcon("Book") {
        moveTo(12f, 6.5f)
        quadTo(8.5f, 4.2f, 3.5f, 4.8f)
        lineTo(3.5f, 18.3f)
        quadTo(8.5f, 17.8f, 12f, 20f)
        quadTo(15.5f, 17.8f, 20.5f, 18.3f)
        lineTo(20.5f, 4.8f)
        quadTo(15.5f, 4.2f, 12f, 6.5f)
        close()
        moveTo(12f, 6.5f)
        lineTo(12f, 20f)
    }

    val Headphones: ImageVector = lineIcon("Headphones") {
        moveTo(4f, 17f)
        lineTo(4f, 13f)
        arcToRelative(8f, 8f, 0f, false, true, 16f, 0f)
        lineTo(20f, 17f)
        moveTo(4f, 14.5f)
        lineTo(7f, 14.5f)
        lineTo(7f, 20f)
        lineTo(5.5f, 20f)
        quadTo(4f, 20f, 4f, 18.5f)
        close()
        moveTo(20f, 14.5f)
        lineTo(17f, 14.5f)
        lineTo(17f, 20f)
        lineTo(18.5f, 20f)
        quadTo(20f, 20f, 20f, 18.5f)
        close()
    }

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

    /** A circled check: "watched". */
    val CheckCircle: ImageVector = lineIcon("CheckCircle") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        close()
        moveTo(8f, 12.3f)
        lineTo(11f, 15.2f)
        lineTo(16.2f, 9.2f)
    }

    /** An empty circle: "not watched". */
    val Circle: ImageVector = lineIcon("Circle") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        close()
    }

    val ChevronLeft: ImageVector = lineIcon("ChevronLeft") {
        moveTo(14.5f, 5.5f)
        lineTo(8f, 12f)
        lineTo(14.5f, 18.5f)
    }

    val ChevronDown: ImageVector = lineIcon("ChevronDown") {
        moveTo(5.5f, 9.5f)
        lineTo(12f, 16f)
        lineTo(18.5f, 9.5f)
    }

    val Close: ImageVector = lineIcon("Close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    val Eye: ImageVector = lineIcon("Eye") {
        moveTo(2.5f, 12f)
        quadTo(7f, 5f, 12f, 5f)
        quadTo(17f, 5f, 21.5f, 12f)
        quadTo(17f, 19f, 12f, 19f)
        quadTo(7f, 19f, 2.5f, 12f)
        close()
        moveTo(12f, 9f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
        close()
    }

    val EyeOff: ImageVector = lineIcon("EyeOff") {
        moveTo(2.5f, 12f)
        quadTo(7f, 5f, 12f, 5f)
        quadTo(17f, 5f, 21.5f, 12f)
        quadTo(17f, 19f, 12f, 19f)
        quadTo(7f, 19f, 2.5f, 12f)
        close()
        moveTo(4f, 4f)
        lineTo(20f, 20f)
    }

    val SkipPrevious: ImageVector = lineIcon("SkipPrevious") {
        moveTo(18f, 6f)
        lineTo(9f, 12f)
        lineTo(18f, 18f)
        close()
        moveTo(6f, 6f)
        lineTo(6f, 18f)
    }

    val SkipNext: ImageVector = lineIcon("SkipNext") {
        moveTo(6f, 6f)
        lineTo(15f, 12f)
        lineTo(6f, 18f)
        close()
        moveTo(18f, 6f)
        lineTo(18f, 18f)
    }

    val Shuffle: ImageVector = lineIcon("Shuffle") {
        moveTo(4f, 7f)
        lineTo(7.5f, 7f)
        lineTo(16f, 17f)
        lineTo(20f, 17f)
        moveTo(4f, 17f)
        lineTo(7.5f, 17f)
        lineTo(10.3f, 13.7f)
        moveTo(13.7f, 10.3f)
        lineTo(16f, 7f)
        lineTo(20f, 7f)
        moveTo(17.5f, 4.5f)
        lineTo(20f, 7f)
        lineTo(17.5f, 9.5f)
        moveTo(17.5f, 14.5f)
        lineTo(20f, 17f)
        lineTo(17.5f, 19.5f)
    }

    val Repeat: ImageVector = lineIcon("Repeat") { repeatArrows() }

    val RepeatOne: ImageVector = lineIcon("RepeatOne") {
        repeatArrows()
        moveTo(11f, 10.8f)
        lineTo(12.4f, 10f)
        lineTo(12.4f, 14f)
    }

    val Star: ImageVector = lineIcon("Star") { star() }

    val StarFilled: ImageVector = ImageVector.Builder("StarFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = { star() },
        )
    }.build()

    /** Lines with a play mark: the play queue. */
    val Queue: ImageVector = lineIcon("Queue") {
        moveTo(4f, 6f)
        lineTo(18f, 6f)
        moveTo(4f, 11f)
        lineTo(18f, 11f)
        moveTo(4f, 16f)
        lineTo(10f, 16f)
        moveTo(14f, 13.5f)
        lineTo(19.5f, 16.75f)
        lineTo(14f, 20f)
        close()
    }

    /** A speech bubble with text lines: lyrics. */
    val Lyrics: ImageVector = lineIcon("Lyrics") {
        roundedRect(3.5f, 4.5f, 20.5f, 16f, 2.5f)
        moveTo(8f, 16f)
        lineTo(7f, 20f)
        lineTo(12f, 16f)
        moveTo(7.5f, 8.5f)
        lineTo(16.5f, 8.5f)
        moveTo(7.5f, 12f)
        lineTo(13f, 12f)
    }

    /** A crescent moon: the sleep timer. */
    val Moon: ImageVector = lineIcon("Moon") {
        moveTo(19.5f, 14.5f)
        arcTo(8f, 8f, 0f, true, true, 9.5f, 4.5f)
        arcTo(6.3f, 6.3f, 0f, false, false, 19.5f, 14.5f)
        close()
    }

    /** A window inside a screen: picture-in-picture. */
    val Pip: ImageVector = lineIcon("Pip") {
        roundedRect(3f, 5f, 21f, 19f, 2f)
        roundedRect(12f, 12f, 18f, 16.5f, 1f)
    }

    /** Arrow around a circle, counter-clockwise; mirror it for forward skips. */
    val SkipBackCircle: ImageVector = lineIcon("SkipBackCircle") {
        moveTo(5.2f, 9.5f)
        arcTo(8f, 8f, 0f, true, true, 4f, 13f)
        moveTo(4.6f, 5.2f)
        lineTo(5.2f, 9.5f)
        lineTo(9.4f, 9f)
    }

    val Gear: ImageVector = lineIcon("Gear") {
        moveTo(12f, 9f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
        close()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            moveTo(12f + 5.5f * c, 12f + 5.5f * sn)
            lineTo(12f + 8.5f * c, 12f + 8.5f * sn)
        }
    }

    private fun PathBuilder.repeatArrows() {
        moveTo(4f, 11.5f)
        lineTo(4f, 9.5f)
        quadTo(4f, 7f, 6.5f, 7f)
        lineTo(19f, 7f)
        moveTo(16.5f, 4.5f)
        lineTo(19f, 7f)
        lineTo(16.5f, 9.5f)
        moveTo(20f, 12.5f)
        lineTo(20f, 14.5f)
        quadTo(20f, 17f, 17.5f, 17f)
        lineTo(5f, 17f)
        moveTo(7.5f, 14.5f)
        lineTo(5f, 17f)
        lineTo(7.5f, 19.5f)
    }

    private fun PathBuilder.star() {
        moveTo(12f, 3.6f)
        lineTo(14.29f, 9.44f)
        lineTo(20.56f, 9.82f)
        lineTo(15.71f, 13.81f)
        lineTo(17.29f, 19.88f)
        lineTo(12f, 16.5f)
        lineTo(6.71f, 19.88f)
        lineTo(8.29f, 13.81f)
        lineTo(3.44f, 9.82f)
        lineTo(9.71f, 9.44f)
        close()
    }

    private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        moveTo(left + r, top)
        lineTo(right - r, top)
        quadTo(right, top, right, top + r)
        lineTo(right, bottom - r)
        quadTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadTo(left, bottom, left, bottom - r)
        lineTo(left, top + r)
        quadTo(left, top, left + r, top)
        close()
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
