package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.AddonSearch
import app.antresol.core.model.AddonWay
import app.antresol.core.model.WayRole
import app.antresol.core.model.WayScope
import app.antresol.core.model.WayTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A movie or series the ways act on: its TMDB id, and whether the library has it. */
data class WayTarget(
    val kind: String,
    val tmdbId: Long,
    val inLibrary: Boolean,
    /** What the player shows while it opens. */
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
)

/** One title section of an addon's search. */
data class AddonSection(val addonId: String, val label: String, val titles: List<WayTitle>)

/**
 * What the profile's addons offer the apps, in the order of the addons' ids (as the web orders
 * them) and, inside an addon, the order it gave. Empty ([NONE]) without addons, for a kid profile,
 * and for addons that do not speak the protocol.
 */
class AddonWays(val ways: List<AddonWay>, val searches: List<AddonSearch>) {
    val isEmpty: Boolean get() = ways.isEmpty() && searches.isEmpty()

    /**
     * The ways to offer for [target], or for one of its episodes ([episode]; then only the ways
     * that run for an episode): the kind and scope must fit, and a role the app does not know is
     * left out. An episode counts as in the library by [episodeInLibrary].
     */
    fun forTarget(target: WayTarget, episode: Boolean = false, episodeInLibrary: Boolean = target.inLibrary): List<AddonWay> {
        val inLibrary = if (episode) episodeInLibrary else target.inLibrary
        return ways.filter { way ->
            target.kind in way.kinds &&
                (way.role == WayRole.WATCH || way.role == WayRole.KEEP) &&
                (!episode || way.episodes) &&
                when (way.scope) {
                    WayScope.ANY -> true
                    WayScope.OUTSIDE -> !inLibrary
                    WayScope.INSIDE -> inLibrary
                    else -> false
                }
        }
    }

    fun watchWays(target: WayTarget): List<AddonWay> = forTarget(target).filter { !it.isKeep }

    fun keepWays(target: WayTarget): List<AddonWay> = forTarget(target).filter { it.isKeep }

    /** Runs every addon's title search for [query]; emits the sections found so far, after each addon answers. */
    fun search(api: WaysApi, query: String): Flow<List<AddonSection>> = channelFlow {
        for (search in searches) {
            launch {
                val titles = try {
                    api.search(search.addonId, query).titles.filter { it.tmdbId > 0 && it.kind.isNotBlank() }.distinctBy { it.kind to it.tmdbId }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (titles.isNotEmpty()) send(AddonSection(search.addonId, search.label, titles))
            }
        }
    }.scan(emptyList()) { sections, next -> (sections + next).sortedBy { section -> searches.indexOfFirst { it.addonId == section.addonId } } }

    companion object {
        val NONE = AddonWays(emptyList(), emptyList())

        /**
         * Asks the server which addons the profile has and what each offers. Any answer other than
         * a good one (403 for a kid, 404 for an addon without a part for the apps, a server error,
         * an unreadable answer) leaves that addon out; [complete] is false only when the network
         * failed, so the caller asks again next time instead of keeping a gap.
         */
        suspend fun discover(api: WaysApi): Discovery {
            val complete = AtomicBoolean(true)
            val ids = try {
                api.addons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                return Discovery(NONE, complete = true)
            } catch (e: IOException) {
                return Discovery(NONE, complete = false)
            } catch (e: Exception) {
                return Discovery(NONE, complete = true)
            }
            val infos = coroutineScope {
                ids.distinct().sorted().map { id ->
                    id to async {
                        try {
                            api.info(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            null
                        } catch (e: IOException) {
                            complete.set(false)
                            null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.map { (id, info) -> id to info.await() }
            }
            val ways = ArrayList<AddonWay>()
            val searches = ArrayList<AddonSearch>()
            for ((id, info) in infos) {
                if (info == null) continue
                info.ways.filter { it.id.isNotBlank() }.mapTo(ways) { it.copy(addonId = id) }
                info.search?.label?.takeIf { it.isNotBlank() }?.let { searches += AddonSearch(id, it) }
            }
            return Discovery(AddonWays(ways, searches), complete.get())
        }
    }

    /** What [discover] found; [complete] is false when a network failure may have hidden an addon. */
    data class Discovery(val ways: AddonWays, val complete: Boolean)
}

/** Where the screens get the [AddonWays] of the session. */
interface WaysSource {
    suspend fun get(): AddonWays

    /** The next [get] asks the server again. */
    fun invalidate()
}

/**
 * The [AddonWays] of the signed-in profile, asked for once and kept for the session: another
 * profile or server (a different token or address) asks again, and so does [invalidate] (the
 * home screen's pull to refresh, a way the server no longer knows).
 */
class WaysCatalog(private val client: () -> AntresolClient?) : WaysSource {
    private val mutex = Mutex()
    @Volatile private var key: String? = null
    @Volatile private var cached: AddonWays? = null

    /** The ways of the current session; [AddonWays.NONE] when nobody is signed in. */
    override suspend fun get(): AddonWays {
        val current = client() ?: return AddonWays.NONE
        val owner = keyOf(current)
        return mutex.withLock {
            if (key == owner) cached?.let { return@withLock it }
            val found = AddonWays.discover(current.waysApi())
            if (found.complete) {
                key = owner
                cached = found.ways
            }
            found.ways
        }
    }

    /** What is known now, without asking; null before the first [get] of this session. */
    fun peek(): AddonWays? {
        val current = client() ?: return null
        return if (key == keyOf(current)) cached else null
    }

    /** The next [get] asks the server again. */
    override fun invalidate() {
        key = null
        cached = null
    }

    private fun keyOf(client: AntresolClient) = "${client.baseUrl}\u0000${client.token}"
}
