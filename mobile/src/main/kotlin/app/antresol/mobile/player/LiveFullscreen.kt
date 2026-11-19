package app.antresol.mobile.player

import android.content.pm.ActivityInfo

/**
 * Full screen for the live player, as video apps do it on a phone: turning the phone to
 * landscape is full screen (immersive, the system bars hidden), back to portrait leaves it; the
 * full-screen button turns the picture itself (a landscape or portrait lock) and lets the sensor
 * take over again once the phone is held that way — but only with auto-rotate on, or the system
 * would turn it straight back. A tablet (smallest width 600 dp and up) keeps its orientation and
 * toggles full screen in place. Picture-in-picture is always the picture alone.
 */
object LiveFullscreen {
    /** Smallest width from which the screen is a tablet's: the button no longer turns the picture. */
    const val TABLET_SMALLEST_WIDTH_DP = 600

    /** Whether the player shows the picture alone, edge to edge. */
    fun isFullscreen(landscape: Boolean, smallestWidthDp: Int, userFullscreen: Boolean, pictureInPicture: Boolean): Boolean =
        pictureInPicture || userFullscreen || (landscape && smallestWidthDp < TABLET_SMALLEST_WIDTH_DP)

    /** What the full-screen button does on this screen. */
    sealed interface Toggle {
        /** Turn the picture: request [orientation] until the phone is held that way. */
        data class Rotate(val orientation: Int, val landscape: Boolean) : Toggle

        /** A tablet: full screen on/off in place. */
        data class InPlace(val fullscreen: Boolean) : Toggle
    }

    fun toggle(fullscreen: Boolean, smallestWidthDp: Int, userFullscreen: Boolean): Toggle = when {
        smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP || userFullscreen -> Toggle.InPlace(!fullscreen)
        fullscreen -> Toggle.Rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, landscape = false)
        else -> Toggle.Rotate(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, landscape = true)
    }

    /**
     * The phone at [degrees] (OrientationEventListener's, −1 unknown) is held the way the lock asked
     * ([landscape]): the lock may give way to the sensor. With some margin, so a phone on its way
     * round does not release it early.
     */
    fun heldAsLocked(degrees: Int, landscape: Boolean): Boolean {
        if (degrees < 0) return false
        val d = degrees % 360
        return if (landscape) d in 60..120 || d in 240..300 else d <= 25 || d >= 335
    }
}
