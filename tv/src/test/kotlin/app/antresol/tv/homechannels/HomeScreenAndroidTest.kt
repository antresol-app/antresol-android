package app.antresol.tv.homechannels

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tvprovider.media.tv.TvContractCompat
import app.antresol.core.model.Kind
import app.antresol.tv.MainActivity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * The parts of the home screen job that touch Android and can be checked without a TV provider: the program objects
 * the provider is given, the tile's intent against the app's manifest, the picture provider and the stored prefs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeScreenAndroidTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val authority get() = HomeArtworkFiles.authority(context)

    @Before
    fun clean() {
        HomeArtworkFiles.dir(context).deleteRecursively()
        HomeChannelPrefs(context).apply {
            channelId = null
            browsableRequested = false
        }
    }

    @After
    fun cleanAfter() {
        HomeArtworkFiles.dir(context).deleteRecursively()
    }

    private val episode = card(
        601, Kind.EPISODE, title = "Episode 2", subtitle = "The Test Show · Season 1, Episode 2", year = 2021,
        thumb = "/frame/601", progress = 0.4, season = 1, episode = 2,
    )

    private val nonce = "feedfacefeedfacefeedfacefeedface"

    private fun continueSpec(rank: Int = 0, positionMs: Long = 1_080_000, durationMs: Long = 2_700_000) =
        HomeMapper.watchNext(snapshot(listOf(started(episode, positionMs, durationMs)))).single()
            .copy(rank = rank, posterUri = "content://$authority/${"a".repeat(64)}", linkNonce = nonce)

    @Test
    fun aContinueTileCarriesItsPositionLengthPictureAndLink() {
        val program = ProgramBuilders.watchNext(context, continueSpec(rank = 2), nowMs = 10_000_000)

        assertEquals(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE, program.type)
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, program.watchNextType)
        assertEquals(1_080_000, program.lastPlaybackPositionMillis)
        assertEquals(2_700_000, program.durationMillis)
        assertEquals("The Test Show", program.title)
        assertEquals("Season 1, Episode 2", program.description)
        assertEquals("Episode 2", program.episodeTitle)
        assertEquals("1", program.seasonNumber)
        assertEquals("2", program.episodeNumber)
        assertEquals("content://$authority/${"a".repeat(64)}", program.posterArtUri.toString())
        assertEquals(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9, program.posterArtAspectRatio)
        assertEquals("wn:srv:7:episode:601", program.internalProviderId)
        assertEquals(continueSpec(rank = 2).fingerprint(), program.internalProviderFlag1)
        // Newest first: the row is ordered by the last engagement.
        assertEquals(10_000_000 - 2 * ProgramBuilders.ENGAGEMENT_STEP_MS, program.lastEngagementTimeUtcMillis)
    }

    @Test
    fun aNextUpTileHasNoPositionOrLength() {
        val spec = HomeMapper.watchNext(snapshot(listOf(ContinueItem(card(602, Kind.EPISODE, subtitle = "Show · S1E3"), null)))).single()
            .copy(posterUri = "content://$authority/x")
        val program = ProgramBuilders.watchNext(context, spec, nowMs = 5_000)

        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT, program.watchNextType)
        // Not set at all (the library reports -1 for a column that was left out).
        assertEquals(-1, program.lastPlaybackPositionMillis)
        assertEquals(-1, program.durationMillis)
    }

    @Test
    fun aMovieOfTheChannelIsAPosterWithAWeightAndItsPage() {
        val spec = HomeMapper.channel(snapshot(movies = listOf(card(1, year = 2020), card(2)))).first()
            .copy(posterUri = "content://$authority/b")
        val program = ProgramBuilders.preview(context, spec, channelId = 42)

        assertEquals(42L, program.channelId)
        assertEquals(TvContractCompat.PreviewProgramColumns.TYPE_MOVIE, program.type)
        assertEquals(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3, program.posterArtAspectRatio)
        assertEquals(HomeMapper.MAX_CHANNEL, program.weight)
        assertEquals("2020", program.releaseDate)
        assertEquals("ch:srv:7:movie:1", program.internalProviderId)
        assertEquals(spec.fingerprint(), program.internalProviderFlag1)
        assertEquals(HomeMapper.MAX_CHANNEL - 3, ProgramBuilders.preview(context, spec.copy(rank = 3), 42).weight)
    }

    @Test
    fun aTilesIntentOpensThisAppsActivityAtTheLink() {
        val intent = ProgramBuilders.watchNext(context, continueSpec(), nowMs = 1).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("antresol://play/601?u=7&n=$nonce", intent.data.toString())
        assertEquals(context.packageName, intent.`package`)

        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(listOf(MainActivity::class.java.name), handlers.map { it.activityInfo.name })

        val open = ProgramBuilders.preview(
            context, HomeMapper.channel(snapshot(series = listOf(card(10, Kind.SERIES)))).single().copy(linkNonce = nonce), 1,
        ).intent
        assertEquals("antresol://title/series/10?u=7&n=$nonce", open.data.toString())
        assertEquals(1, context.packageManager.queryIntentActivities(open, 0).size)
    }

    @Test
    fun theLibrarysEpgPermissionsAreNotRemovedFromTheManifest() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertTrue(requested.toString(), "com.android.providers.tv.permission.WRITE_EPG_DATA" in requested)
        assertTrue(requested.toString(), "com.android.providers.tv.permission.READ_EPG_DATA" in requested)
    }

    @Test
    fun onlyTheAppsOwnAddressesReachTheActivity() {
        fun handlers(uri: String) = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName), 0).size
        assertEquals(1, handlers("antresol://play/19?u=1"))
        assertEquals(1, handlers("antresol://title/movie/19?u=1"))
        assertEquals(0, handlers("antresol://settings/1"))
        assertEquals(0, handlers("https://example.com/play/19"))
    }

    @Test
    fun theChannelsLogoOpensTheAppItself() {
        // The channel's app link is the activity as it is (no deep link).
        assertNotNull(Intent(context, MainActivity::class.java).resolveActivity(context.packageManager))
    }

    // The picture provider

    private fun provider() = Robolectric.setupContentProvider(HomeArtworkProvider::class.java, authority)

    private fun cached(name: String, content: ByteArray): Uri {
        HomeArtworkFiles.dir(context).mkdirs()
        File(HomeArtworkFiles.dir(context), name).writeBytes(content)
        return Uri.parse("content://$authority/$name")
    }

    @Test
    fun theLauncherCanReadACachedPicture() {
        provider()
        val name = HomeArtworkFiles.name("s", "/p/1")
        val uri = cached(name, byteArrayOf(1, 2, 3, 4))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(name, cursor.getString(cursor.getColumnIndexOrThrow("_display_name")))
            assertEquals(4L, cursor.getLong(cursor.getColumnIndexOrThrow("_size")))
        }
    }

    @Test
    fun theProviderServesNothingElseAndWritesNothing() {
        val provider = provider()
        val name = HomeArtworkFiles.name("s", "/p/1")
        val uri = cached(name, byteArrayOf(1))
        File(context.filesDir, "secret.txt").writeText("no")

        fun denied(candidate: Uri) = try {
            context.contentResolver.openInputStream(candidate)!!.close()
            false
        } catch (_: FileNotFoundException) {
            true
        }
        assertTrue(denied(Uri.parse("content://$authority/missing")))
        assertTrue(denied(Uri.parse("content://$authority/${"0".repeat(64)}"))) // valid shape, no file
        assertTrue(denied(Uri.parse("content://$authority/..%2Fsecret.txt")))
        assertTrue(denied(Uri.parse("content://$authority/tv-home%2F$name")))
        assertTrue(denied(Uri.parse("content://$authority/$name/extra")))
        assertNull(context.contentResolver.getType(Uri.parse("content://$authority/missing")))

        listOf("w", "rw", "wt", "wa").forEach { mode ->
            try {
                provider.openFile(uri, mode)!!.close()
                fail("a write was allowed in mode $mode")
            } catch (_: FileNotFoundException) {
            }
        }
        assertEquals(0, context.contentResolver.delete(uri, null, null))
        assertNull(context.contentResolver.insert(uri, ContentValues()))
        assertEquals(1, File(HomeArtworkFiles.dir(context), name).readBytes().size)
    }

    // The prefs

    @Test
    fun theArtworkSecretIsCreatedOnceAndKept() {
        val first = HomeChannelPrefs(context).artworkSecret
        assertEquals(32, first.length)
        assertEquals(first, HomeChannelPrefs(context).artworkSecret)
    }

    @Test
    fun theLinkNonceIsRandomCreatedOnceAndKept() {
        val first = HomeChannelPrefs(context).linkNonce
        assertEquals(32, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertEquals(first, HomeChannelPrefs(context).linkNonce)
        // It is not the picture secret.
        assertTrue(first != HomeChannelPrefs(context).artworkSecret)
    }

    @Test
    fun aTilesAddressIsAcceptedWithTheNonceItWasBuiltWithAndNotWithout() {
        val nonce = HomeChannelPrefs(context).linkNonce
        val intent = HomeLink.Play(5, 7).intent(context, nonce)
        assertEquals(HomeLink.Play(5, 7), HomeLinks.parse(intent.dataString, nonce))
        assertNull(HomeLinks.parse("antresol://play/5?u=7", nonce))
    }

    @Test
    fun thePictureFolderIsInTheAppsFilesNotInItsCache() {
        val dir = HomeArtworkFiles.dir(context)
        assertEquals(File(context.filesDir, "tv-home"), dir)
        assertFalse(dir.path.startsWith(context.cacheDir.path))
    }

    @Test
    fun theChannelIdAndTheOneTimeRequestAreRemembered() {
        val prefs = HomeChannelPrefs(context)
        assertNull(prefs.channelId)
        assertFalse(prefs.browsableRequested)
        prefs.channelId = 12
        prefs.browsableRequested = true
        assertEquals(12L, HomeChannelPrefs(context).channelId)
        assertTrue(HomeChannelPrefs(context).browsableRequested)
    }
}
