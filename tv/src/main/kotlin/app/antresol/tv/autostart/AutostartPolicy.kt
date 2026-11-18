package app.antresol.tv.autostart

import android.os.Build
import app.antresol.tv.settings.TvPreferences
import kotlinx.coroutines.delay

/** What «Открывать Antresol при включении» shows once the app is in front. */
enum class AutostartTarget(val key: String) {
    /** The app as it was left (its task is just brought forward; a cold start opens Home). */
    Last("last"),

    /** The «ТВ» tab. */
    Tv("tv"),

    /** The channel this profile watched last, playing. */
    Channel("channel"),
    ;

    companion object {
        fun fromKey(key: String?): AutostartTarget = entries.firstOrNull { it.key == key } ?: Last
    }
}

/** Why the device asks the app to open. */
enum class AutostartTrigger {
    /** The device started (`BOOT_COMPLETED`). */
    Boot,

    /** The screen came on: the device woke from standby, or the user unlocked it. */
    Wake,
}

/**
 * A request for the shell to open [target], made by [AutostartLauncher] and carried to the shell by
 * the launch intent. It is dropped when it is old (the app then started for another reason).
 */
data class AutostartRequest(val target: AutostartTarget, val atMs: Long) {
    fun fresh(nowMs: Long): Boolean = nowMs - atMs in 0..MAX_AGE_MS

    companion object {
        /** Long enough for a cold start that restores the session first. */
        const val MAX_AGE_MS = 60_000L
    }
}

/**
 * The rules of the autostart, apart from Android so they can be tested. Why it works the way it does:
 *
 * - Since Android 10 an app in the background cannot start an activity, with a few exceptions. A
 *   boot receiver or a service is not one; the permission «Display over other apps»
 *   (`SYSTEM_ALERT_WINDOW`) the user grants is ([needsOverlayPermission]). Before Android 10 there is no restriction.
 * - `ACTION_SCREEN_ON` cannot be declared in the manifest, so waking is watched by a receiver that
 *   lives in a foreground service ([wakeWatchWanted]); only `BOOT_COMPLETED` is a manifest receiver.
 * - At boot and on wake the TV's home screen starts about the same time and covers the app, so
 *   the launch waits a little and is repeated until the app really is in front.
 */
object AutostartPolicy {
    /** After the boot broadcast: the home screen starts later than this broadcast and would cover the app. */
    const val BOOT_DELAY_MS = 8_000L

    /** After the screen came on: lets the system bring back what was in front first (the app itself, if it was). */
    const val WAKE_DELAY_MS = 1_500L

    /** How long a launch has to bring the app forward before it is made again. */
    const val RETRY_MS = 3_000L

    /** Launches in a row before giving up. */
    const val ATTEMPTS = 3

    /** `ACTION_MANAGE_OVERLAY_PERMISSION` grants matter from Android 10 (API 29). */
    fun needsOverlayPermission(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.Q

    /** The foreground service that watches the screen runs only when the user asked for it and it could open the app. */
    fun wakeWatchWanted(prefs: TvPreferences, permissionGranted: Boolean): Boolean =
        prefs.autostart && prefs.autostartOnWake && permissionGranted

    fun initialDelayMs(trigger: AutostartTrigger): Long = when (trigger) {
        AutostartTrigger.Boot -> BOOT_DELAY_MS
        AutostartTrigger.Wake -> WAKE_DELAY_MS
    }

    /** Null when the app should be opened now; else why not. */
    fun skipReason(
        prefs: TvPreferences,
        trigger: AutostartTrigger,
        permissionGranted: Boolean,
        appVisible: Boolean,
        interactive: Boolean,
    ): Skip? = when {
        !prefs.autostart -> Skip.Off
        trigger == AutostartTrigger.Wake && !prefs.autostartOnWake -> Skip.WakeOff
        !permissionGranted -> Skip.NoPermission
        // The viewer was in the app when the device went to sleep: it is back in front, left as it was.
        appVisible -> Skip.AlreadyVisible
        // The screen went off again meanwhile.
        trigger == AutostartTrigger.Wake && !interactive -> Skip.ScreenOff
        else -> null
    }

    enum class Skip { Off, WakeOff, NoPermission, AlreadyVisible, ScreenOff }
}

/** What one [AutostartRunner.run] did. */
sealed interface AutostartOutcome {
    /** The app came to the front after [attempts] launches (or already was, after the first). */
    data class Opened(val attempts: Int) : AutostartOutcome

    data class Skipped(val reason: AutostartPolicy.Skip) : AutostartOutcome

    /** The launches were made but the app did not come to the front (something else keeps covering it). */
    data object GaveUp : AutostartOutcome
}

/**
 * One autostart: waits for the system to settle, launches, and launches again while the app is not
 * in front. Everything it needs from Android is passed in, so the sequence can be tested with virtual time.
 */
class AutostartRunner(
    private val prefs: () -> TvPreferences,
    private val permissionGranted: () -> Boolean,
    private val appVisible: () -> Boolean,
    private val interactive: () -> Boolean,
    private val launch: (AutostartTarget) -> Unit,
) {
    suspend fun run(trigger: AutostartTrigger): AutostartOutcome {
        delay(AutostartPolicy.initialDelayMs(trigger))
        var launches = 0
        while (launches < AutostartPolicy.ATTEMPTS) {
            val current = prefs()
            val skip = AutostartPolicy.skipReason(current, trigger, permissionGranted(), appVisible(), interactive())
            if (skip != null) {
                // Seen in front after our own launch: done. Before any launch: nothing to do.
                return if (launches > 0 && skip == AutostartPolicy.Skip.AlreadyVisible) AutostartOutcome.Opened(launches) else AutostartOutcome.Skipped(skip)
            }
            launch(AutostartTarget.fromKey(current.autostartTarget))
            launches++
            delay(AutostartPolicy.RETRY_MS)
        }
        return if (appVisible()) AutostartOutcome.Opened(launches) else AutostartOutcome.GaveUp
    }
}
