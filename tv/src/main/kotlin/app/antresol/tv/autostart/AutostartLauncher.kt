package app.antresol.tv.autostart

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.antresol.tv.MainActivity

/** Counts the app's started activities: the app is in front for the viewer while it is above zero. */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0

    val isVisible: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** The intent that brings the app forward and tells its shell what to open. */
object AutostartLauncher {
    const val ACTION_AUTOSTART = "app.antresol.tv.action.AUTOSTART"
    const val EXTRA_TARGET = "app.antresol.tv.extra.AUTOSTART_TARGET"

    fun intent(context: Context, target: AutostartTarget): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_AUTOSTART)
            .putExtra(EXTRA_TARGET, target.key)
            // singleTask: a task that exists is brought forward as it is.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts the app from the background (the caller has checked [OverlayPermission]). */
    fun launch(context: Context, target: AutostartTarget) {
        context.startActivity(intent(context, target))
    }
}
