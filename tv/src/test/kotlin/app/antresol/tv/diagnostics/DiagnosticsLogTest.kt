package app.antresol.tv.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kept log: one line per event with date, pid and tag; the oldest half goes once a file is full. */
class DiagnosticsLogTest {
    private val dir: File = Files.createTempDirectory("diag").toFile()
    private var now = 1_764_100_000_000L

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun log(pid: Int = 42, maxBytes: Int = DiagnosticsLog.MAX_BYTES) =
        DiagnosticsLog(dir, pid, maxBytes, clock = { now }, executor = { it.run() })

    @Test
    fun linesCarryPidTagAndTextOnOneLine() {
        log().write("hb", "avail=400MB\nsecond line", durable = true)
        val line = log().lines().single()
        assertEquals(42, DiagnosticsLog.pidOf(line))
        assertEquals("hb", DiagnosticsLog.tagOf(line))
        assertTrue(line, line.endsWith(" 42 hb avail=400MB second line"))
    }

    @Test
    fun aFullFileMovesAsideAndOnlyTwoAreKept() {
        val log = log(maxBytes = 200)
        repeat(30) { log.write("hb", "line $it ${"x".repeat(40)}") }
        val lines = log.lines()
        assertTrue("kept ${lines.size}", lines.size in 4..10)
        assertTrue(lines.last(), lines.last().contains("line 29 "))
        assertEquals(lines.map { it.substringAfter(" hb line ").substringBefore(' ').toInt() }.sorted(), lines.map { it.substringAfter(" hb line ").substringBefore(' ').toInt() })
        assertEquals(setOf("log.txt", "log.1.txt"), dir.list()!!.toSet())
    }

    @Test
    fun noFolderYetMeansNoLines() {
        assertEquals(emptyList<String>(), DiagnosticsLog(File(dir, "missing"), 1, executor = { it.run() }).lines())
    }
}
