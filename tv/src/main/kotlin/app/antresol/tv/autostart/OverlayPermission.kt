package app.antresol.tv.autostart

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * «Display over other apps» (`SYSTEM_ALERT_WINDOW`): the one permission that lets the app start
 * its screen from the background on Android 10+ (see [AutostartPolicy]). The app draws no overlay.
 * Android TV has no standard place that asks for it, and some TV firmware has no settings page for
 * it at all, so the grant is guided and has a manual fallback ([adbCommand]).
 */
object OverlayPermission {
    fun granted(context: Context): Boolean = !AutostartPolicy.needsOverlayPermission() || Settings.canDrawOverlays(context)

    /** From the most specific settings page to the most general one. */
    fun settingsIntents(context: Context): List<Intent> {
        val uri = Uri.parse("package:${context.packageName}")
        return listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
        )
    }

    /** Opens the first of [settingsIntents] this device has; false when it has none (show [adbCommand]). */
    fun openSettings(context: Context): Boolean {
        for (intent in settingsIntents(context)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Next one.
            } catch (_: SecurityException) {
                // Next one.
            }
        }
        return false
    }

    /** For a TV whose firmware cannot show the setting: the same grant from a computer. */
    fun adbCommand(context: Context): String = "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
}
