package app.antresol.tv.homechannels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Kind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The whole run against fakes: what is written to the home screen, and when nothing is. */
class HomeSyncTest {
    private class FakeSource(var snapshot: HomeSnapshot? = null, var failure: Exception? = null, var signedIn: Boolean = true) : HomeSource {
        var loads = 0
        override suspend fun load(): HomeSnapshot? {
            loads++
            failure?.let { throw it }
            return snapshot
        }

        override fun signedIn() = signedIn
    }

    private class FakeArtwork : HomeArtwork {
        val missing = mutableSetOf<String>()
        var retained: Set<String>? = null
        override val placeholderUri = "android.resource://pkg/drawable/tv_banner"
        override suspend fun uriFor(ref: ArtworkRef): String? =
            listOf(ref.path, ref.fallbackPath).firstOrNull { it.isNotEmpty() && it !in missing }?.let { "content://pkg.homeart/${it.hashCode()}" }

        override fun retain(uris: Set<String>) {
            retained = uris
        }
    }

    /** A provider in memory: rows by id, with what the app stored in them. */
    private class FakeStore : HomeStore {
        val watchNext = linkedMapOf<Long, ProgramSpec>()
        val channelRows = linkedMapOf<Long, ProgramSpec>()
        var channel: ChannelState? = null
        var nextId = 100L
        val writes = mutableListOf<String>()

        override fun storedWatchNext() = watchNext.map { (id, spec) -> StoredProgram(id, spec.key, spec.fingerprint()) }

        override fun storedChannel(channelId: Long) = channelRows.map { (id, spec) -> StoredProgram(id, spec.key, spec.fingerprint()) }

        override fun applyWatchNext(plan: Plan, nowMs: Long) {
            plan.remove.forEach { watchNext.remove(it); writes += "wn-remove $it" }
            plan.update.forEach { (id, spec) -> watchNext[id] = spec; writes += "wn-update $id" }
            plan.add.forEach { spec -> watchNext[nextId++] = spec; writes += "wn-add ${spec.key}" }
        }

        override fun applyChannel(channelId: Long, plan: Plan) {
            plan.remove.forEach { channelRows.remove(it); writes += "ch-remove $it" }
            plan.update.forEach { (id, spec) -> channelRows[id] = spec; writes += "ch-update $id" }
            plan.add.forEach { spec -> channelRows[nextId++] = spec; writes += "ch-add ${spec.key}" }
        }

        override fun ensureChannel(): ChannelState? = (channel ?: ChannelState(nextId++, browsable = false).also { channel = it; writes += "channel-created" })

        override fun clearPrograms(): Int {
            val removed = watchNext.size + channelRows.size
            watchNext.clear()
            channelRows.clear()
            if (removed > 0) writes += "cleared $removed"
            return removed
        }
    }

    private val source = FakeSource()
    private val artwork = FakeArtwork()
    private val store = FakeStore()
    private val sync = HomeSync(source, artwork, store, clock = { 1_000_000L })

    private val movie = card(19, backdrop = "/bd/19", progress = 0.1, year = 2019)
    private val episode = card(601, Kind.EPISODE, title = "Episode 2", subtitle = "Show · S1:E2", thumb = "/frame/601", progress = 0.4)

    private fun full() = snapshot(
        continueItems = listOf(started(episode), started(movie)),
        movies = listOf(card(1), card(2)),
        series = listOf(card(10, Kind.SERIES)),
    )

    @Test
    fun theFirstRunFillsWatchNextAndCreatesTheChannelWithItsPrograms() = runTest {
        source.snapshot = full()
        val result = sync.run() as HomeSyncResult.Synced

        assertEquals(2, store.watchNext.size)
        assertEquals(listOf("wn:srv:7:episode:601", "wn:srv:7:movie:19"), store.watchNext.values.map { it.key })
        assertEquals(3, store.channelRows.size)
        assertEquals(2, result.watchNext.add.size)
        assertEquals(3, result.channel.add.size)
        assertEquals(store.channel, result.channelState)
        // Each program carries the picture the launcher can read.
        assertTrue(store.watchNext.values.all { it.posterUri!!.startsWith("content://pkg.homeart/") })
        assertEquals(store.watchNext.values.mapNotNull { it.posterUri }.toSet() + store.channelRows.values.mapNotNull { it.posterUri }, artwork.retained)
    }

    @Test
    fun anUnchangedServerChangesNothing() = runTest {
        source.snapshot = full()
        sync.run()
        store.writes.clear()

        val again = sync.run() as HomeSyncResult.Synced
        assertTrue(again.watchNext.isEmpty && again.channel.isEmpty)
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun playingMoreUpdatesWatchNextWholeAndLeavesTheChannelAlone() = runTest {
        source.snapshot = full()
        sync.run()
        store.writes.clear()

        source.snapshot = full().copy(continueItems = listOf(started(episode, positionMs = 1_800_000), started(movie)))
        val result = sync.run() as HomeSyncResult.Synced

        assertEquals(2, result.watchNext.update.size)
        assertTrue(result.watchNext.add.isEmpty() && result.watchNext.remove.isEmpty())
        assertTrue(result.channel.isEmpty)
        assertEquals(1_800_000, store.watchNext.values.first().positionMs)
    }

    @Test
    fun aTitleThatIsFinishedOrRemovedOnTheServerLeavesWatchNext() = runTest {
        source.snapshot = full()
        sync.run()

        // The movie was finished: the server's continue list no longer has it.
        source.snapshot = full().copy(continueItems = listOf(started(episode)))
        val result = sync.run() as HomeSyncResult.Synced

        assertEquals(1, result.watchNext.remove.size)
        assertEquals(listOf("wn:srv:7:episode:601"), store.watchNext.values.map { it.key })
    }

    @Test
    fun aTitleThatTheServerNowCountsAsFinishedIsRemovedEvenWhenStillListed() = runTest {
        source.snapshot = full()
        sync.run()
        source.snapshot = full().copy(continueItems = listOf(started(episode), started(movie, finished = true)))
        sync.run()
        assertEquals(listOf("wn:srv:7:episode:601"), store.watchNext.values.map { it.key })
    }

    @Test
    fun aNewRecentTitleAddsToTheChannelAndAnOldOneLeavesIt() = runTest {
        source.snapshot = full()
        sync.run()
        source.snapshot = full().copy(recentMovies = listOf(card(3), card(1)), recentSeries = emptyList())
        val result = sync.run() as HomeSyncResult.Synced

        assertEquals(listOf("ch:srv:7:movie:3", "ch:srv:7:movie:1"), store.channelRows.values.sortedBy { it.rank }.map { it.key })
        assertTrue(result.channel.remove.isNotEmpty())
    }

    @Test
    fun nobodySignedInRemovesEverythingAndTouchesNeitherTheServerNorTheChannel() = runTest {
        source.snapshot = full()
        sync.run()
        store.writes.clear()

        source.snapshot = null
        val result = sync.run()

        assertEquals(HomeSyncResult.SignedOut(5), result)
        assertTrue(store.watchNext.isEmpty() && store.channelRows.isEmpty())
        assertEquals(setOf<String>(), artwork.retained)
        // Only the clearing was done: no channel was created or touched.
        assertEquals(listOf("cleared 5"), store.writes)
    }

    @Test
    fun signedOutWithNothingToRemoveCreatesNothing() = runTest {
        source.snapshot = null
        val result = sync.run()
        assertEquals(HomeSyncResult.SignedOut(0), result)
        assertNull(store.channel)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun aRevokedSessionIsASignOut() = runTest {
        source.snapshot = full()
        sync.run()
        source.failure = ApiException(401, "Unauthorized")
        source.signedIn = false

        assertEquals(HomeSyncResult.SignedOut(5), sync.run())
        assertTrue(store.watchNext.isEmpty())
    }

    @Test
    fun anotherProfileGetsItsOwnTilesAndTheOldOnesGo() = runTest {
        source.snapshot = full()
        sync.run()

        source.snapshot = snapshot(continueItems = listOf(started(card(77, progress = 0.2))), userId = 8)
        sync.run()

        assertEquals(listOf("wn:srv:8:movie:77"), store.watchNext.values.map { it.key })
        assertTrue(store.channelRows.isEmpty())
    }

    @Test
    fun aServerThatCannotBeReachedLeavesWhatWasThere() = runTest {
        source.snapshot = full()
        sync.run()
        store.writes.clear()

        source.failure = IOException("offline")
        val result = sync.run()

        assertTrue(result is HomeSyncResult.Failed)
        assertEquals(2, store.watchNext.size)
        assertEquals(3, store.channelRows.size)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun anErrorOtherThanASignOutIsAFailureNotASignOut() = runTest {
        source.failure = ApiException(500, "Server error")
        assertTrue(sync.run() is HomeSyncResult.Failed)
    }

    @Test
    fun aWatchNextTileWithoutAPictureGetsTheAppsOwnBannerAndAChannelTileIsLeftOut() = runTest {
        artwork.missing += setOf("/bd/19", "/api/images/local/2/poster")
        source.snapshot = full()
        sync.run()

        val movieTile = store.watchNext.values.first { it.key == "wn:srv:7:movie:19" }
        assertEquals(artwork.placeholderUri, movieTile.posterUri)
        assertEquals(Aspect.Wide, movieTile.aspect)
        assertFalse(store.channelRows.values.any { it.key == "ch:srv:7:movie:2" })
        assertEquals(2, store.channelRows.size)
    }

    @Test
    fun aPictureThatArrivesLaterReplacesTheBanner() = runTest {
        artwork.missing += "/bd/19"
        source.snapshot = full()
        sync.run()
        artwork.missing.clear()

        val result = sync.run() as HomeSyncResult.Synced
        assertTrue(result.watchNext.update.isNotEmpty())
        assertTrue(store.watchNext.values.none { it.posterUri == artwork.placeholderUri })
    }

    @Test
    fun theCardsOfAnEmptyLibraryProduceAnEmptyHomeScreen() = runTest {
        source.snapshot = snapshot()
        val result = sync.run() as HomeSyncResult.Synced
        assertTrue(result.watchNext.isEmpty && result.channel.isEmpty)
        assertEquals(1, source.loads)
    }
}
