package app.antresol.tv.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How the system says one run of the app ended (Android 11+ `ApplicationExitInfo`). */
data class ExitRecord(
    val pid: Int,
    val timeMs: Long,
    /** «ANR», «LOW_MEMORY», «CRASH», … */
    val reason: String,
    val description: String,
    val pssKb: Long,
    val rssKb: Long,
    /** The top of the main thread's stack for an ANR (from its trace), else empty. */
    val mainStack: List<String> = emptyList(),
)

/**
 * The text behind Settings → Diagnostics, made only from what the device kept: the system's last
 * exits of the app and the app's own log ([DiagnosticsLog]). Pure, so it is tested on the JVM.
 */
object DiagnosticsReport {
    /** Lines of the previous run kept at the next start: enough to see memory and players move. */
    const val TAIL_LINES = 12

    /**
     * What ended the run before this one. [lines] is the log before this run wrote its start; the
     * previous run is the one that wrote the last «start». A run the system has no exit for (a power
     * cut, a reboot, a device that froze) is told by its last lines. Null without a previous run.
     */
    fun previousRun(lines: List<String>, exits: List<ExitRecord>): PreviousRun? {
        val pid = lines.lastOrNull { DiagnosticsLog.tagOf(it) == "start" }?.let(DiagnosticsLog::pidOf) ?: return null
        val exit = exits.firstOrNull { it.pid == pid }
        val head = if (exit != null) {
            listOf("end pid=$pid ${describe(exit)}") + exit.mainStack.map { "  $it" }
        } else {
            listOf("end pid=$pid unexplained: no exit from the system (power cut, reboot or frozen device)")
        }
        val tail = lines.filter { DiagnosticsLog.pidOf(it) == pid }.takeLast(TAIL_LINES).map(::tailLine)
        return PreviousRun(head + tail, notable = exit == null || exit.reason in NOTABLE)
    }

    /**
     * The account of the previous run ([lines]); [notable] when it did not end the ordinary way (the
     * user or the system closing an app it no longer needs): kept until another notable end replaces it.
     */
    data class PreviousRun(val lines: List<String>, val notable: Boolean)

    /** Ends worth keeping: the app or the device in trouble. */
    private val NOTABLE = setOf("ANR", "CRASH", "CRASH_NATIVE", "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "INITIALIZATION_FAILURE")

    /** «  21:30:00 hb …»: a kept line without its date and pid. */
    private fun tailLine(line: String): String {
        val parts = line.split(' ', limit = 4)
        return if (parts.size == 4) "  ${parts[1]} ${parts[3]}" else "  $line"
    }

    /** «LOW_MEMORY 2026-11-25 21:30:00 pss=180MB rss=240MB "description"». */
    fun describe(exit: ExitRecord): String = buildString {
        append(exit.reason).append(' ').append(format(exit.timeMs))
        if (exit.pssKb > 0) append(" pss=").append(exit.pssKb / 1024).append("MB")
        if (exit.rssKb > 0) append(" rss=").append(exit.rssKb / 1024).append("MB")
        if (exit.description.isNotBlank()) append(" \"").append(exit.description.take(MAX_DESCRIPTION)).append('"')
    }

    /**
     * The top [frames] frames of the main thread in an ANR trace (the «"main" prio=5 tid=1 …» block of
     * the system's dump), without the «at » prefixes.
     */
    fun mainThreadStack(trace: String, frames: Int = 8): List<String> {
        val lines = trace.lineSequence().iterator()
        while (lines.hasNext()) {
            if (lines.next().startsWith("\"main\"")) break
        }
        val stack = ArrayList<String>()
        while (lines.hasNext() && stack.size < frames) {
            val line = lines.next().trim()
            if (line.isEmpty()) break
            if (line.startsWith("at ") || line.startsWith("native: ") || line.startsWith("- ")) stack += line.removePrefix("at ")
        }
        return stack
    }

    /**
     * The whole report: [header] lines (device, memory now), the kept account of the previous run
     * ([previous]), the system's last [exits] and the last lines of the log; at most [maxChars] (the
     * QR code's room), the oldest log lines dropped first.
     */
    fun compose(header: List<String>, previous: List<String>, exits: List<ExitRecord>, lines: List<String>, maxChars: Int = Int.MAX_VALUE): String {
        val fixed = buildList {
            addAll(header)
            if (previous.isNotEmpty()) {
                add("-- last end")
                addAll(previous)
            }
            if (exits.isNotEmpty()) {
                add("-- exits")
                exits.take(MAX_EXITS).forEach { add("pid=${it.pid} ${describe(it)}") }
            }
        }
        val fixedText = fixed.joinToString("\n")
        if (fixedText.length >= maxChars) return fixedText.take(maxChars)
        val log = ArrayList<String>()
        var room = maxChars - fixedText.length - LOG_HEADER.length - 1
        for (line in lines.asReversed()) {
            val short = line.substringAfter(' ')
            if (short.length + 1 > room) break
            log += short
            room -= short.length + 1
        }
        if (log.isEmpty()) return fixedText
        return fixedText + "\n" + LOG_HEADER + "\n" + log.asReversed().joinToString("\n")
    }

    private fun format(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    private const val LOG_HEADER = "-- log"
    private const val MAX_EXITS = 5
    private const val MAX_DESCRIPTION = 120
}
