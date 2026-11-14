package app.antresol.core.discovery

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Health
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Runs every [DiscoverySource] while collected, merges what they see by server id and confirms
 * each server with `GET /api/health` ([health]) on its candidates in order: the first one that
 * answers with the same `serverId` becomes the server's address. Unconfirmed servers are never
 * listed. New candidates of a server that could not be confirmed are tried again.
 *
 * `searching` is true for the first [searchWindowMs] (one UDP round plus mDNS resolving).
 */
class ServerDiscovery(
    private val sources: List<DiscoverySource>,
    private val health: suspend (baseUrl: String) -> Health?,
    private val searchWindowMs: Long = 4_000,
) : ServerFinder {
    override fun states(): Flow<DiscoveryState> = channelFlow {
        val lock = Mutex()
        val sightings = mutableMapOf<String, ServerSighting>()
        val confirmed = linkedMapOf<String, DiscoveredServer>()
        val tried = mutableMapOf<String, MutableSet<String>>()
        val checking = mutableMapOf<String, Job>()
        var searching = true

        suspend fun publish() {
            val state = lock.withLock {
                DiscoveryState(confirmed.values.sortedWith(compareBy({ it.name.lowercase() }, { it.address })), searching)
            }
            send(state)
        }

        fun check(id: String) {
            if (checking[id]?.isActive == true) return
            checking[id] = launch {
                while (true) {
                    val next = lock.withLock {
                        if (confirmed.containsKey(id)) return@withLock null
                        val sighting = sightings.getValue(id)
                        val done = tried.getOrPut(id) { mutableSetOf() }
                        sighting.candidates.firstOrNull { it !in done }?.also { done += it }?.let { it to sighting }
                    } ?: break
                    val (url, sighting) = next
                    val answer = try {
                        health(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (answer != null && answer.ok && answer.serverId.lowercase() == id) {
                        lock.withLock {
                            confirmed[id] = DiscoveredServer(
                                id = id,
                                name = sighting.name.ifBlank { "Antresol" },
                                version = answer.version.ifBlank { sighting.version },
                                baseUrl = url,
                            )
                        }
                        publish()
                        break
                    }
                }
            }
        }

        publish()
        launch {
            delay(searchWindowMs)
            lock.withLock { searching = false }
            publish()
        }
        merge(*sources.map { source -> source.sightings() }.toTypedArray()).collect { sighting ->
            val changed = lock.withLock {
                val merged = Sightings.merge(sightings[sighting.id], sighting)
                sightings[sighting.id] = merged
                // A confirmed server keeps its address; a new name or version still updates the row.
                val known = confirmed[sighting.id]
                if (known != null && (merged.name.isNotBlank() && merged.name != known.name)) {
                    confirmed[sighting.id] = known.copy(name = merged.name)
                    true
                } else {
                    false
                }
            }
            if (changed) publish()
            check(sighting.id)
        }
    }

    companion object {
        /** A health check with short timeouts: a stale LAN address should not hold the list up. */
        fun quickHealthCheck(base: OkHttpClient = AntresolClient.defaultHttpClient()): suspend (String) -> Health? {
            val http = base.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build()
            return { url -> AntresolClient(url, http).health() }
        }
    }
}
