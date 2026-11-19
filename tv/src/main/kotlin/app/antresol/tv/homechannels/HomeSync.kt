package app.antresol.tv.homechannels

import app.antresol.core.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the server's answer comes from. */
interface HomeSource {
    /**
     * What the signed-in profile should see, or null when nobody is signed in (it restores the stored session first).
     * Throws when the server cannot be asked (an [ApiException] with 401: the session is gone).
     */
    suspend fun load(): HomeSnapshot?

    /** Whether a profile is signed in right now (after a failed [load], to tell a lost session from a lost network). */
    fun signedIn(): Boolean
}

/** The system's TV provider, as [HomeSync] needs it. Every call may block on the provider (run it off the main thread). */
interface HomeStore {
    /** Whether the device has the TV provider at all; when not, nothing else of the store may be called. */
    fun available(): Boolean = true

    fun storedWatchNext(): List<StoredProgram>

    /** The programs of the app channel [channelId]. */
    fun storedChannel(channelId: Long): List<StoredProgram>

    fun applyWatchNext(plan: Plan, nowMs: Long)

    fun applyChannel(channelId: Long, plan: Plan)

    /** The «Antresol» channel, created when the app has none; null when the provider refuses it. */
    fun ensureChannel(): ChannelState?

    /**
     * Removes every program of this app from Watch Next and from the channel (the channel itself stays; none is
     * created). Returns how many were removed.
     */
    fun clearPrograms(): Int
}

/** [browsable]: the user (or the system) already lets the channel show on the home screen. */
data class ChannelState(val id: Long, val browsable: Boolean)

/** Pictures the launcher can read (it is another process and cannot send the server's login). */
interface HomeArtwork {
    /** A URI of [ref] the launcher can open, caching it first; null when neither of its pictures could be had. */
    suspend fun uriFor(ref: ArtworkRef): String?

    /** A picture of the app itself, for a Watch Next tile whose own picture could not be had. */
    val placeholderUri: String

    /** Forgets every cached picture except those behind [uris]. */
    fun retain(uris: Set<String>)
}

sealed interface HomeSyncResult {
    /** Nobody is signed in: the programs of the app were removed. */
    data class SignedOut(val removed: Int) : HomeSyncResult

    data class Synced(val watchNext: Plan, val channel: Plan, val channelState: ChannelState?) : HomeSyncResult

    /** The device has no TV provider: nothing was asked of the server or changed, and nothing is worth scheduling. */
    data object Unsupported : HomeSyncResult

    /** Nothing was changed. */
    data class Failed(val cause: Throwable) : HomeSyncResult
}

/**
 * One run of the whole job: ask the server, make the programs, cache their pictures, compare them with what the
 * system holds and apply the differences. Runs never overlap. A run that cannot reach the server changes nothing.
 */
class HomeSync(
    private val source: HomeSource,
    private val artwork: HomeArtwork,
    private val store: HomeStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The install's link nonce, given to every program ([ProgramSpec.linkNonce]). */
    private val linkNonce: String = "",
) {
    private val mutex = Mutex()

    suspend fun run(): HomeSyncResult = mutex.withLock {
        try {
            if (!store.available()) return@withLock HomeSyncResult.Unsupported
            val snapshot = try {
                source.load()
            } catch (e: ApiException) {
                // The session was revoked: whatever the profile had on the home screen goes.
                if (e.isUnauthorized) null else throw e
            }
            if (snapshot == null) {
                return@withLock HomeSyncResult.SignedOut(clear())
            }
            sync(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!source.signedIn()) HomeSyncResult.SignedOut(runCatching { clear() }.getOrDefault(0)) else HomeSyncResult.Failed(e)
        }
    }

    private fun clear(): Int = store.clearPrograms().also { artwork.retain(emptySet()) }

    private suspend fun sync(snapshot: HomeSnapshot): HomeSyncResult {
        val watchNext = withPictures(HomeMapper.watchNext(snapshot), placeholder = true)
        val channelPrograms = withPictures(HomeMapper.channel(snapshot), placeholder = false)

        val now = clock()
        val watchNextPlan = HomeDiff.plan(store.storedWatchNext(), watchNext, rewriteAllOnChange = true)
        if (!watchNextPlan.isEmpty) store.applyWatchNext(watchNextPlan, now)

        val channel = store.ensureChannel()
        val channelPlan = if (channel == null) {
            Plan()
        } else {
            HomeDiff.plan(store.storedChannel(channel.id), channelPrograms).also { if (!it.isEmpty) store.applyChannel(channel.id, it) }
        }

        artwork.retain((watchNext + channelPrograms).mapNotNullTo(HashSet()) { it.posterUri })
        return HomeSyncResult.Synced(watchNextPlan, channelPlan, channel)
    }

    /**
     * Gives each program the URI of its cached picture. One with no picture gets the app's own (a wide banner) in Watch
     * Next, and is left out of the channel, whose tiles are all posters.
     */
    private suspend fun withPictures(specs: List<ProgramSpec>, placeholder: Boolean): List<ProgramSpec> =
        specs.mapNotNull { spec ->
            val uri = artwork.uriFor(spec.artwork)
            when {
                uri != null -> spec.copy(posterUri = uri, linkNonce = linkNonce)
                placeholder -> spec.copy(posterUri = artwork.placeholderUri, aspect = Aspect.Wide, linkNonce = linkNonce)
                else -> null
            }
        }
}
