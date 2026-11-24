package app.antresol.mobile.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The PDFs kept for reading again stay within a size: the least recently opened go first. */
class PdfCachePruneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A cached PDF of [size] bytes in server folder [server], last opened [age] seconds ago. */
    private fun pdf(server: String, id: Int, size: Int, age: Long): File {
        val dir = File(tmp.root, server).apply { mkdirs() }
        return File(dir, "$id.pdf").apply {
            writeBytes(ByteArray(size))
            setLastModified(1_700_000_000_000 - age * 1_000)
        }
    }

    @Test
    fun underTheLimitNothingIsDeleted() {
        val a = pdf("s1", 1, 40, age = 30)
        val b = pdf("s1", 2, 40, age = 10)
        prunePdfCache(tmp.root, keep = b, maxBytes = 100)
        assertTrue(a.exists() && b.exists())
    }

    @Test
    fun theLeastRecentlyOpenedGoFirstAcrossServers() {
        val oldest = pdf("s1", 1, 40, age = 300)
        val old = pdf("s2", 2, 40, age = 200)
        val recent = pdf("s1", 3, 40, age = 100)
        val opened = pdf("s2", 4, 40, age = 0)
        prunePdfCache(tmp.root, keep = opened, maxBytes = 100)
        assertFalse(oldest.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists() && opened.exists())
    }

    @Test
    fun theBookBeingOpenedIsNeverDeletedEvenWhenItIsTheOldestOrTooBigAlone() {
        val big = pdf("s1", 1, 300, age = 500)
        val other = pdf("s1", 2, 40, age = 100)
        prunePdfCache(tmp.root, keep = big, maxBytes = 100)
        assertTrue(big.exists())
        assertFalse(other.exists())
    }

    @Test
    fun onlyFinishedPdfsCountAndAMissingFolderIsFine() {
        val partial = File(File(tmp.root, "s1").apply { mkdirs() }, "1-123.part").apply { writeBytes(ByteArray(500)) }
        val kept = pdf("s1", 2, 40, age = 0)
        prunePdfCache(tmp.root, keep = kept, maxBytes = 100)
        assertTrue(partial.exists() && kept.exists())
        prunePdfCache(File(tmp.root, "nothing"), keep = kept)
        assertEquals(1, File(tmp.root, "s1").listFiles { f -> f.extension == "pdf" }!!.size)
    }
}
