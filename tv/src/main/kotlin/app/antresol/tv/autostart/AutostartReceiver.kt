package app.antresol.tv.autostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The manifest receiver of the two broadcasts that survive the background limits: the device
 * started (open the app, and watch the screen if asked), and the app was updated (the update
 * stopped the service: start it again, without opening the app).
 */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> AutostartController.sync(context, boot = true)
            Intent.ACTION_MY_PACKAGE_REPLACED -> AutostartController.sync(context)
        }
    }
}
