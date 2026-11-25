package app.antresol.tv.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.annotation.RequiresApi
import app.antresol.tv.BuildConfig
import app.antresol.ui.playback.PlayerCensus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the TV keeps for the next time it hangs (the owner's projector froze until it was unplugged,
 * with no cable to read its logs): a heartbeat with the memory and the players every minute while
 * the app is in front (every ten behind), memory warnings, crashes, players
 * and decoder errors ([PlayerCensus]), and at the next start how the system says the previous run
 * ended (Android 11+), with the last lines of that run. Settings → Diagnostics shows it all on the
 * screen and as a QR code; nothing is sent anywhere.
 */
class Diagnostics(private val context: Context, private val visible: () -> Boolean) {
    // Not `filesDir`: on the first run it makes the folder, a disk write on the main thread.
    private val dir = File(context.dataDir, "files/diagnostics")
    val log = DiagnosticsLog(dir, Process.myPid())
    private val previousFile = File(dir, "previous.txt")
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun start(scope: CoroutineScope) {
        PlayerCensus.log = { log.write("player", it, durable = true) }
        if (crashHandlerInstalled.compareAndSet(false, true)) {
            val crashes = Thread.getDefaultUncaughtExceptionHandler()
            val now = DiagnosticsLog(dir, Process.myPid(), executor = { it.run() })
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Written here and now: the process is about to go.
                runCatching { now.write("crash", "${thread.name}: ${error.stackTraceToString().lineSequence().take(CRASH_LINES).joinToString(" | ")}", durable = true) }
                crashes?.uncaughtException(thread, error)
            }
        }
        scope.launch(Dispatchers.IO) {
            val previous = DiagnosticsReport.previousRun(log.lines(), exits(withTraces = true))
            // Kept until the next end in trouble: the account of a freeze must survive the ordinary ends after it.
            if (previous?.notable == true) runCatching { dir.mkdirs(); previousFile.writeText(previous.lines.joinToString("\n")) }
            previous?.lines?.firstOrNull()?.let { log.write("prev", it) }
            log.write("start", "v${BuildConfig.VERSION_NAME} ${device()} ${memory()}", durable = true)
            // Every minute in front, every [BACKGROUND_EVERY] minutes behind (decided at each beat:
            // the process starts before its activity is in front).
            var beats = 0L
            while (true) {
                delay(HEARTBEAT_MS)
                beats++
                if (visible() || beats % BACKGROUND_EVERY == 0L) log.write("hb", "${memory()} ${PlayerCensus.summary()} fg=${visible()}", durable = true)
            }
        }
    }

    /** The system asks for memory back: noted with what the app holds then. */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        log.write("trim", "level=$level ${memory()} ${PlayerCensus.summary()}", durable = true)
    }

    /** The report for the screen and its QR code (at most [maxChars]); reads files: call off the main thread. */
    suspend fun report(maxChars: Int = Int.MAX_VALUE): String = withContext(Dispatchers.IO) {
        val previous = runCatching { previousFile.readLines() }.getOrDefault(emptyList())
        val header = listOf("Antresol TV v${BuildConfig.VERSION_NAME} ${device()}", "now ${memory()} ${PlayerCensus.summary()}")
        DiagnosticsReport.compose(header, previous, exits(withTraces = false), log.lines(), maxChars)
    }

    /** «Manufacturer Model Android 11 (API 30) ram=1475MB lowRam=true class=128MB». */
    private fun device(): String {
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
            "ram=${memory.totalMem / MB}MB lowRam=${activityManager?.isLowRamDevice} class=${activityManager?.memoryClass}MB"
    }

    /** «avail=412MB low=false heap=23/192MB native=28MB threads=52». */
    private fun memory(): String {
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val heap = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val threads = File("/proc/self/task").list()?.size ?: Thread.activeCount()
        return "avail=${memory.availMem / MB}MB low=${memory.lowMemory} heap=$heap/${runtime.maxMemory() / MB}MB " +
            "native=${Debug.getNativeHeapAllocatedSize() / MB}MB threads=$threads"
    }

    private fun exits(withTraces: Boolean): List<ExitRecord> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { systemExits(withTraces) }.getOrDefault(emptyList()) else emptyList()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun systemExits(withTraces: Boolean): List<ExitRecord> =
        activityManager?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS).orEmpty().map { exit ->
            val stack = if (withTraces && exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.use { stream ->
                        val bytes = ByteArray(TRACE_BYTES)
                        var size = 0
                        while (size < bytes.size) {
                            val read = stream.read(bytes, size, bytes.size - size)
                            if (read < 0) break
                            size += read
                        }
                        DiagnosticsReport.mainThreadStack(bytes.decodeToString(0, size))
                    }
                }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            ExitRecord(exit.pid, exit.timestamp, reasonName(exit.reason), exit.description.orEmpty(), exit.pss, exit.rss, stack)
        }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    private companion object {
        /** One handler per process (tests make several applications in one). */
        val crashHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
        const val MB = 1024L * 1024
        const val HEARTBEAT_MS = 60_000L
        const val BACKGROUND_EVERY = 10L
        const val MAX_EXITS = 5
        const val TRACE_BYTES = 64 * 1024
        const val CRASH_LINES = 8
    }
}
