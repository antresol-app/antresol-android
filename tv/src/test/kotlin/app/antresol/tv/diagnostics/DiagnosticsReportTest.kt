package app.antresol.tv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the TV tells the administrator what ended its previous run, and the report's room in a QR code. */
class DiagnosticsReportTest {
    private val previousRun = listOf(
        "2026-11-25 20:00:00 100 start v1 box",
        "2026-11-25 20:01:00 100 hb avail=400MB players=0",
        "2026-11-25 21:00:00 200 start v1 box",
        "2026-11-25 21:01:00 200 player player+ video: 2 players alive",
        "2026-11-25 21:02:00 200 hb avail=31MB low=true players=2 video=2",
    )

    @Test
    fun aRunTheSystemSaysNothingAboutIsUnexplainedAndKeepsItsLastLines() {
        val run = DiagnosticsReport.previousRun(previousRun, exits = listOf(exit(pid = 100, reason = "LOW_MEMORY")))!!
        assertTrue(run.notable)
        assertTrue(run.lines.first(), run.lines.first().startsWith("end pid=200 unexplained"))
        assertEquals(listOf("  21:00:00 start v1 box", "  21:01:00 player player+ video: 2 players alive", "  21:02:00 hb avail=31MB low=true players=2 video=2"), run.lines.drop(1))
    }

    @Test
    fun anAnrIsToldWithTheTopOfTheMainThread() {
        val anr = exit(pid = 200, reason = "ANR", description = "Input dispatching timed out", stack = listOf("java.net.SocketInputStream.read(Native method)"))
        val run = DiagnosticsReport.previousRun(previousRun, listOf(anr))!!
        assertTrue(run.notable)
        assertTrue(run.lines[0], run.lines[0].startsWith("end pid=200 ANR 20") && run.lines[0].endsWith("pss=180MB rss=250MB \"Input dispatching timed out\""))
        assertEquals("  java.net.SocketInputStream.read(Native method)", run.lines[1])
    }

    @Test
    fun anOrdinaryEndIsNotKept() {
        assertFalse(DiagnosticsReport.previousRun(previousRun, listOf(exit(pid = 200, reason = "USER_REQUESTED")))!!.notable)
    }

    @Test
    fun noStartMeansNoPreviousRun() {
        assertNull(DiagnosticsReport.previousRun(emptyList(), emptyList()))
    }

    @Test
    fun theMainThreadIsReadFromAnAnrTrace() {
        val trace = """
            ----- pid 200 at 2026-11-25 21:02:10 -----
            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at some.Other.frame(Other.java:1)

            "main" prio=5 tid=1 Native
              | group="main" sCount=1
              native: #00 pc 000a1b2c  /apex/libc.so (read+8)
              at java.net.SocketInputStream.socketRead0(Native method)
              at okhttp3.internal.Foo.bar(Foo.kt:10)
              - locked <0x0abc> (a java.lang.Object)
              at app.antresol.Thing.load(Thing.kt:5)

            "OkHttp" prio=5 tid=20 Waiting
              at x.y(Z.java:1)
        """.trimIndent()
        assertEquals(
            listOf("native: #00 pc 000a1b2c  /apex/libc.so (read+8)", "java.net.SocketInputStream.socketRead0(Native method)", "okhttp3.internal.Foo.bar(Foo.kt:10)"),
            DiagnosticsReport.mainThreadStack(trace, frames = 3),
        )
    }

    @Test
    fun theReportFitsItsRoomDroppingTheOldestLogLinesFirst() {
        val log = (1..200).map { "2026-11-25 21:%02d:00 200 hb avail=${it}MB".format(it % 60) }
        val report = DiagnosticsReport.compose(listOf("Antresol TV v1 box"), listOf("end pid=100 unexplained"), listOf(exit(100, "CRASH")), log, maxChars = 400)
        assertTrue(report.length <= 400)
        assertTrue(report, report.startsWith("Antresol TV v1 box\n-- last end\nend pid=100 unexplained\n-- exits\npid=100 CRASH"))
        assertTrue(report, report.endsWith("avail=200MB"))
        assertFalse(report, report.contains("avail=1MB"))
    }

    private fun exit(pid: Int, reason: String, description: String = "", stack: List<String> = emptyList()) =
        ExitRecord(pid, 1_764_100_000_000L, reason, description, pssKb = 180 * 1024, rssKb = 250 * 1024, mainStack = stack)
}
