package app.antresol.tv.homechannels

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The pictures of the tiles: cached files the launcher can read, named so that nothing else of the app is opened. */
class HomeArtworkTest {
    private lateinit var dir: File
    private var now = 1_000_000_000L
    private val fetched = mutableListOf<String>()
    private val bytes = mutableMapOf<String, ByteArray?>()
    private var encoded: ((ByteArray) -> ByteArray?) = { it.reversedArray() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tv-home-art").toFile().resolve("cache")
    }

    @After
    fun tearDown() {
        dir.parentFile.deleteRecursively()
    }

    private fun store() = HomeArtworkStore(
        dir = dir, secret = "s3cret", authority = "pkg.homeart", placeholderUri = "android.resource://pkg/drawable/tv_banner",
        fetch = { path -> fetched += path; bytes[path] }, encode = { encoded(it) }, clock = { now },
    )

    private fun fileOf(uri: String) = File(dir, uri.substringAfterLast('/'))

    @Test
    fun namesAreDigestsOfTheInstallsSecretAndThePath() {
        val a = HomeArtworkFiles.name("s3cret", "/api/images/local/1/poster")
        assertTrue(HomeArtworkFiles.isName(a))
        assertEquals(64, a.length)
        assertEquals(a, HomeArtworkFiles.name("s3cret", "/api/images/local/1/poster"))
        assertNotEquals(a, HomeArtworkFiles.name("s3cret", "/api/images/local/2/poster"))
        assertNotEquals(a, HomeArtworkFiles.name("another", "/api/images/local/1/poster"))
    }

    @Test
    fun onlyTheDigestShapedNamesAreServed() {
        listOf(null, "", "../x", "abc", "A".repeat(64), "g".repeat(64), "0".repeat(63), "0".repeat(65), "${"0".repeat(64)}.tmp", "${"0".repeat(60)}/../")
            .forEach { assertFalse("$it", HomeArtworkFiles.isName(it)) }
        assertTrue(HomeArtworkFiles.isName("0123456789abcdef".repeat(4)))
    }

    @Test
    fun fileForFindsOnlyAnExistingFileOfAValidName() {
        dir.mkdirs()
        val name = HomeArtworkFiles.name("s", "/p")
        assertNull(HomeArtworkFiles.fileFor(dir, name))
        File(dir, name).writeText("x")
        assertEquals(File(dir, name), HomeArtworkFiles.fileFor(dir, name))
        File(dir.parentFile, "secret.txt").writeText("no")
        assertNull(HomeArtworkFiles.fileFor(dir, "../secret.txt"))
    }

    @Test
    fun aPictureIsFetchedOnceShrunkAndThenServedFromTheFile() = runTest {
        bytes["/p/1"] = byteArrayOf(1, 2, 3)
        val store = store()

        val uri = store.uriFor(ArtworkRef("/p/1"))!!
        assertTrue(uri.startsWith("content://pkg.homeart/"))
        assertTrue(HomeArtworkFiles.isName(uri.substringAfterLast('/')))
        assertArrayEquals(byteArrayOf(3, 2, 1), fileOf(uri).readBytes())

        assertEquals(uri, store.uriFor(ArtworkRef("/p/1")))
        assertEquals(listOf("/p/1"), fetched)
        assertEquals(1, dir.listFiles()!!.size) // no temporary file left behind
    }

    @Test
    fun aPictureOlderThanAWeekIsFetchedAgainAndAFailedRefreshKeepsTheOldFile() = runTest {
        bytes["/p/1"] = byteArrayOf(1)
        val store = store()
        val uri = store.uriFor(ArtworkRef("/p/1"))!!

        now += HomeArtworkStore.TTL_MS + 1
        bytes["/p/1"] = byteArrayOf(9)
        store.uriFor(ArtworkRef("/p/1"))
        assertArrayEquals(byteArrayOf(9), fileOf(uri).readBytes())

        now += HomeArtworkStore.TTL_MS + 1
        bytes["/p/1"] = null
        assertEquals(uri, store.uriFor(ArtworkRef("/p/1")))
        assertArrayEquals(byteArrayOf(9), fileOf(uri).readBytes())
        assertEquals(3, fetched.size)
    }

    @Test
    fun theFallbackPictureIsUsedWhenTheFirstCannotBeHad() = runTest {
        bytes["/frame/1"] = null
        bytes["/bd/1"] = byteArrayOf(5)
        val uri = store().uriFor(ArtworkRef("/frame/1", "/bd/1"))!!

        assertEquals(HomeArtworkFiles.uri("pkg.homeart", HomeArtworkFiles.name("s3cret", "/bd/1")), uri)
        assertEquals(listOf("/frame/1", "/bd/1"), fetched)
    }

    @Test
    fun aPictureAndroidCannotDecodeCountsAsMissing() = runTest {
        bytes["/logo.svg"] = byteArrayOf(1)
        encoded = { null }
        assertNull(store().uriFor(ArtworkRef("/logo.svg")))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun noPathMeansNoPictureAndNoFetch() = runTest {
        assertNull(store().uriFor(ArtworkRef("")))
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun retainDeletesTheFilesNoProgramUsesAnymore() = runTest {
        bytes["/p/1"] = byteArrayOf(1)
        bytes["/p/2"] = byteArrayOf(2)
        val store = store()
        val keep = store.uriFor(ArtworkRef("/p/1"))!!
        val drop = store.uriFor(ArtworkRef("/p/2"))!!

        store.retain(setOf(keep, "android.resource://pkg/drawable/tv_banner"))
        assertTrue(fileOf(keep).isFile)
        assertFalse(fileOf(drop).exists())

        store.retain(emptySet())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun aPictureShrinksByWholeHalvesWhileItStaysAtLeastAsBigAsWanted() {
        assertEquals(1, ArtworkEncoder.sampleSize(480, 270))
        assertEquals(1, ArtworkEncoder.sampleSize(960, 540))
        assertEquals(1, ArtworkEncoder.sampleSize(1500, 900))
        assertEquals(2, ArtworkEncoder.sampleSize(1920, 1080))
        assertEquals(4, ArtworkEncoder.sampleSize(3840, 2160))
        assertEquals(4, ArtworkEncoder.sampleSize(2000, 3840))
    }
}
