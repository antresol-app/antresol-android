package app.antresol.tv.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A small log kept on the device for the next time the box hangs: lines appended to [dir]/log.txt,
 * which moves to log.1.txt once it holds [maxBytes], so at most about twice that is kept. A line
 * that matters after a power cut ([durable]) is synced to the storage at once. Nothing leaves the
 * device: the administrator reads it on the TV (Settings → Diagnostics).
 *
 * Writes run on [executor] (one background thread), never on the caller's.
 */
class DiagnosticsLog(
    private val dir: File,
    private val pid: Int,
    private val maxBytes: Int = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "antresol-diagnostics").apply { isDaemon = true } },
) {
    private val current = File(dir, "log.txt")
    private val previous = File(dir, "log.1.txt")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /** Appends «2026-11-25 21:30:00 [pid] [tag] [text]». */
    fun write(tag: String, text: String, durable: Boolean = false) {
        val line = "${stamp.format(Date(clock()))} $pid $tag ${text.replace('\n', ' ')}\n"
        executor.execute { append(line, durable) }
    }

    /** Every line kept, oldest first (read on the caller's thread: call off the main one). */
    fun lines(): List<String> = listOf(previous, current).flatMap { file ->
        if (file.isFile) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()
    }

    private fun append(line: String, durable: Boolean) {
        runCatching {
            dir.mkdirs()
            if (current.length() >= maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            FileOutputStream(current, true).use { out ->
                out.write(line.toByteArray())
                if (durable) out.fd.sync()
            }
        }
    }

    companion object {
        const val MAX_BYTES = 48 * 1024

        /** A line's pid (the second field after date and time), or null for a line not written by [write]. */
        fun pidOf(line: String): Int? = line.split(' ', limit = 5).getOrNull(2)?.toIntOrNull()

        /** A line's tag (the field after the pid). */
        fun tagOf(line: String): String? = line.split(' ', limit = 5).getOrNull(3)
    }
}
