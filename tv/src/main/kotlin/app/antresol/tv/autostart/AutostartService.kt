package app.antresol.tv.autostart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opens the app after a boot and, while «Открывать Antresol при включении» is on with «После
 * режима ожидания», when the screen comes on. It is a foreground service because a receiver of
 * `ACTION_SCREEN_ON` lives only in a running process (it cannot be declared in the manifest), and
 * a cached process of a TV app is killed whenever the box runs short of memory. When the user did
 * not ask for waking, the service runs just for the boot launch and stops.
 *
 * The notification is the lowest importance and says what the service is for; the service is
 * started only after the user switched the setting on and granted [OverlayPermission], and stops when
 * either is taken back ([AutostartController.sync]).
 */
class AutostartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: IllegalStateException) {
            // The system restarted the service from the background and refused it a notification
            // (Android 12+): the next time the app is opened or the device starts, it is started again.
            Log.w(TAG, "autostart service cannot run in the background now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as AntresolTvApp
        val prefs = app.settings.current
        val permission = OverlayPermission.granted(this)
        if (!prefs.autostart || !permission) {
            stopSelf()
            return START_NOT_STICKY
        }
        val wake = AutostartPolicy.wakeWatchWanted(prefs, permission)
        if (wake) watchScreen() else unwatchScreen()
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) {
            begin(AutostartTrigger.Boot) { if (!wake) stopSelf() }
        } else if (!wake) {
            stopSelf()
        }
        // Restarted by the system after it was killed: the screen is watched again.
        return if (wake) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        unwatchScreen()
        scope.cancel()
        super.onDestroy()
    }

    /** One autostart at a time: a second screen event during the first (screen on, then user present) is the same wake. */
    private fun begin(trigger: AutostartTrigger, done: () -> Unit = {}) {
        if (run?.isActive == true) return
        val app = application as AntresolTvApp
        val power = getSystemService(PowerManager::class.java)
        val runner = AutostartRunner(
            prefs = { app.settings.current },
            permissionGranted = { OverlayPermission.granted(this) },
            appVisible = { app.visibility.isVisible },
            interactive = { power?.isInteractive != false },
            launch = { target ->
                try {
                    AutostartLauncher.launch(this, target)
                } catch (e: Exception) {
                    Log.w(TAG, "autostart launch refused", e)
                }
            },
        )
        run = scope.launch {
            Log.i(TAG, "autostart $trigger: ${runner.run(trigger)}")
            done()
        }
    }

    private fun watchScreen() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                begin(AutostartTrigger.Wake)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Not exported: only the system sends these.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun unwatchScreen() {
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.autostart_notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.autostart_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "AntresolAutostart"
        private const val CHANNEL = "autostart"
        private const val NOTIFICATION_ID = 41
        const val EXTRA_BOOT = "app.antresol.tv.extra.BOOT"

        fun intent(context: Context, boot: Boolean = false): Intent =
            Intent(context, AutostartService::class.java).putExtra(EXTRA_BOOT, boot)
    }
}

/** Starts and stops [AutostartService] to match the settings and the permission. */
object AutostartController {
    /** Call while the app is in front (the settings, [app.antresol.tv.MainActivity.onStart]) or from a boot receiver. */
    fun sync(context: Context, boot: Boolean = false) {
        val app = context.applicationContext as AntresolTvApp
        val prefs = app.settings.current
        if (!prefs.autostart || !OverlayPermission.granted(context)) {
            context.stopService(AutostartService.intent(context))
            return
        }
        // The boot launch needs the service even when waking is not asked for.
        if (boot || AutostartPolicy.wakeWatchWanted(prefs, permissionGranted = true)) {
            ContextCompat.startForegroundService(context, AutostartService.intent(context, boot))
        } else {
            context.stopService(AutostartService.intent(context))
        }
    }
}
