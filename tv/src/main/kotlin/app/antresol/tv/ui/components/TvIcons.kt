package app.antresol.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Line icons of the TV app's music, audiobook, reader and browse screens (same 24-grid style as `AntresolIcons`). */
object TvIcons {
    val Shuffle: ImageVector = lineIcon("Shuffle") {
        moveTo(3f, 7f); lineTo(7f, 7f); curveTo(11f, 7f, 13f, 17f, 17f, 17f); lineTo(21f, 17f)
        moveTo(3f, 17f); lineTo(7f, 17f); curveTo(11f, 17f, 13f, 7f, 17f, 7f); lineTo(21f, 7f)
        moveTo(18.5f, 4.5f); lineTo(21f, 7f); lineTo(18.5f, 9.5f)
        moveTo(18.5f, 14.5f); lineTo(21f, 17f); lineTo(18.5f, 19.5f)
    }

    val Repeat: ImageVector = lineIcon("Repeat") {
        moveTo(4f, 11f); lineTo(4f, 9f); quadTo(4f, 7f, 6f, 7f); lineTo(20f, 7f)
        moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f)
        moveTo(20f, 13f); lineTo(20f, 15f); quadTo(20f, 17f, 18f, 17f); lineTo(4f, 17f)
        moveTo(6.5f, 14.5f); lineTo(4f, 17f); lineTo(6.5f, 19.5f)
    }

    val RepeatOne: ImageVector = lineIcon("RepeatOne") {
        moveTo(4f, 11f); lineTo(4f, 9f); quadTo(4f, 7f, 6f, 7f); lineTo(20f, 7f)
        moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f)
        moveTo(20f, 13f); lineTo(20f, 15f); quadTo(20f, 17f, 18f, 17f); lineTo(4f, 17f)
        moveTo(6.5f, 14.5f); lineTo(4f, 17f); lineTo(6.5f, 19.5f)
        moveTo(11.2f, 11f); lineTo(12.4f, 10f); lineTo(12.4f, 14.2f)
    }

    val Previous: ImageVector = lineIcon("Previous") {
        moveTo(18f, 6f); lineTo(9f, 12f); lineTo(18f, 18f); close()
        moveTo(6f, 6f); lineTo(6f, 18f)
    }

    val Next: ImageVector = lineIcon("Next") {
        moveTo(6f, 6f); lineTo(15f, 12f); lineTo(6f, 18f); close()
        moveTo(18f, 6f); lineTo(18f, 18f)
    }

    /** Counter-clockwise arrow: skip back. */
    val SkipBack: ImageVector = lineIcon("SkipBack") {
        moveTo(5.6f, 9f)
        arcTo(7.5f, 7.5f, 0f, true, true, 4.5f, 12.5f)
        moveTo(5.2f, 4.8f); lineTo(5.6f, 9f); lineTo(9.8f, 8.6f)
    }

    /** Clockwise arrow: skip forward. */
    val SkipForward: ImageVector = lineIcon("SkipForward") {
        moveTo(18.4f, 9f)
        arcTo(7.5f, 7.5f, 0f, true, false, 19.5f, 12.5f)
        moveTo(18.8f, 4.8f); lineTo(18.4f, 9f); lineTo(14.2f, 8.6f)
    }

    val Heart: ImageVector = lineIcon("Heart") { heart() }

    val HeartFilled: ImageVector = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round) { heart() }
    }.build()

    val Lyrics: ImageVector = lineIcon("Lyrics") {
        moveTo(5f, 5f); lineTo(19f, 5f); quadTo(20f, 5f, 20f, 6f); lineTo(20f, 15f); quadTo(20f, 16f, 19f, 16f)
        lineTo(10f, 16f); lineTo(6f, 19.5f); lineTo(6f, 16f); lineTo(5f, 16f); quadTo(4f, 16f, 4f, 15f); lineTo(4f, 6f)
        quadTo(4f, 5f, 5f, 5f); close()
        moveTo(8f, 9f); lineTo(16f, 9f)
        moveTo(8f, 12f); lineTo(13f, 12f)
    }

    val Queue: ImageVector = lineIcon("Queue") {
        moveTo(4f, 6f); lineTo(16f, 6f)
        moveTo(4f, 11f); lineTo(16f, 11f)
        moveTo(4f, 16f); lineTo(11f, 16f)
        moveTo(15f, 14f); lineTo(20f, 17f); lineTo(15f, 20f); close()
    }

    val Speed: ImageVector = lineIcon("Speed") {
        moveTo(4.2f, 16f)
        arcTo(8.5f, 8.5f, 0f, true, true, 19.8f, 16f)
        moveTo(12f, 14f); lineTo(16f, 9f)
    }

    val Moon: ImageVector = lineIcon("Moon") {
        moveTo(19.5f, 14.5f)
        arcTo(8f, 8f, 0f, true, true, 9.5f, 4.5f)
        arcTo(6.5f, 6.5f, 0f, false, false, 19.5f, 14.5f)
        close()
    }

    val MusicNote: ImageVector = lineIcon("MusicNote") {
        moveTo(9f, 17.5f); lineTo(9f, 5.5f); lineTo(19f, 3.5f); lineTo(19f, 15.5f)
        moveTo(6.5f, 15f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, -5f)
        close()
        moveTo(16.5f, 13f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f)
        arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, -5f)
        close()
    }

    val Book: ImageVector = lineIcon("Book") {
        moveTo(12f, 6.5f); quadTo(8.5f, 4f, 4f, 4.5f); lineTo(4f, 18f); quadTo(8.5f, 17.5f, 12f, 20f)
        quadTo(15.5f, 17.5f, 20f, 18f); lineTo(20f, 4.5f); quadTo(15.5f, 4f, 12f, 6.5f); close()
        moveTo(12f, 6.5f); lineTo(12f, 20f)
    }

    val Film: ImageVector = lineIcon("Film") {
        moveTo(5f, 4f); lineTo(19f, 4f); quadTo(20f, 4f, 20f, 5f); lineTo(20f, 19f); quadTo(20f, 20f, 19f, 20f)
        lineTo(5f, 20f); quadTo(4f, 20f, 4f, 19f); lineTo(4f, 5f); quadTo(4f, 4f, 5f, 4f); close()
        moveTo(8f, 4f); lineTo(8f, 20f)
        moveTo(16f, 4f); lineTo(16f, 20f)
        moveTo(4f, 9f); lineTo(8f, 9f); moveTo(4f, 15f); lineTo(8f, 15f)
        moveTo(16f, 9f); lineTo(20f, 9f); moveTo(16f, 15f); lineTo(20f, 15f)
    }

    val Tv: ImageVector = lineIcon("Tv") {
        moveTo(4.5f, 5f); lineTo(19.5f, 5f); quadTo(21f, 5f, 21f, 6.5f); lineTo(21f, 15.5f); quadTo(21f, 17f, 19.5f, 17f)
        lineTo(4.5f, 17f); quadTo(3f, 17f, 3f, 15.5f); lineTo(3f, 6.5f); quadTo(3f, 5f, 4.5f, 5f); close()
        moveTo(8f, 20.5f); lineTo(16f, 20.5f)
    }

    val Headphones: ImageVector = lineIcon("Headphones") {
        moveTo(4f, 17f); lineTo(4f, 12f)
        arcTo(8f, 8f, 0f, false, true, 20f, 12f)
        lineTo(20f, 17f)
        moveTo(4f, 14f); lineTo(7f, 14f); lineTo(7f, 20f); lineTo(5f, 20f); quadTo(4f, 20f, 4f, 19f); close()
        moveTo(20f, 14f); lineTo(17f, 14f); lineTo(17f, 20f); lineTo(19f, 20f); quadTo(20f, 20f, 20f, 19f); close()
    }

    private fun PathBuilder.heart() {
        moveTo(12f, 20f)
        curveTo(5f, 15.5f, 3f, 12f, 3f, 8.8f)
        curveTo(3f, 6.2f, 5f, 4.2f, 7.5f, 4.2f)
        curveTo(9.4f, 4.2f, 11f, 5.3f, 12f, 7f)
        curveTo(13f, 5.3f, 14.6f, 4.2f, 16.5f, 4.2f)
        curveTo(19f, 4.2f, 21f, 6.2f, 21f, 8.8f)
        curveTo(21f, 12f, 19f, 15.5f, 12f, 20f)
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
