package app.antresol.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Optical centring of a glyph in a round button. A glyph that sits in the middle of its 24-unit grid can
 * still look off when its ink is lopsided: the picture-in-picture window pulls to the lower right, the
 * speech bubble's tail and the caption lines to the bottom. The eye centres by the ink, not by the frame,
 * so such an icon is moved a little the other way.
 *
 * The shifts are in grid units (+x right, +y down) and are measured, not guessed: `IconOpticsTest` draws
 * the icons of the round buttons, takes the centre of their ink, and checks this table against 0.6 of the
 * distance from that centre to the middle of the grid. An axis is moved only when the icon's frame is
 * centred on it (the play triangle and the chevrons are already drawn a little toward their tip by hand),
 * and a shift under a quarter of a unit is not worth making.
 */
object IconOptics {
    /** The grid the icons are drawn on. */
    const val GRID = 24f

    private val shifts: Map<String, Offset> = mapOf(
        // The inner window and the corner it sits in pull the mass to the lower right.
        "Pip" to Offset(-0.45f, -0.3f),
        // The bubble's tail and the caption lines weigh the bottom, the tail's side the left.
        "Audio" to Offset(0.25f, 0.5f),
        "Subtitles" to Offset(0f, -0.25f),
        // Two chevrons whose frame sits half a unit left of the grid's middle.
        "SkipAhead" to Offset(0.45f, 0f),
        "SkipNext" to Offset(0.25f, 0f),
        // The channel list's bars lean right, a star's points weigh the top and its body the bottom.
        "List" to Offset(-0.55f, 0f),
        "Star" to Offset(0f, -0.35f),
    )

    /** How far to move [icon] (grid units); none for an icon that is balanced. */
    fun shift(icon: ImageVector): Offset = shifts[icon.name] ?: Offset.Zero

    /** The shifts by icon name, for the test that measures them. */
    val table: Map<String, Offset> get() = shifts
}

/**
 * Moves an icon by its [IconOptics] shift. Apply it after the icon's size (`Modifier.size(22.dp)`), so
 * the shift scales with the glyph.
 */
fun Modifier.opticalOffset(icon: ImageVector): Modifier {
    val shift = IconOptics.shift(icon)
    if (shift == Offset.Zero) return this
    return graphicsLayer {
        translationX = shift.x / IconOptics.GRID * size.width
        translationY = shift.y / IconOptics.GRID * size.height
    }
}
