package app.antresol.core.channels

import app.antresol.core.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where [LastChannels] keeps its ids: SharedPreferences in the apps, a map in tests. */
interface LastChannelStorage {
    fun read(key: String): Long?

    fun write(key: String, channelId: Long)
}

/** A [LastChannelStorage] in memory (tests, previews). */
class InMemoryLastChannelStorage : LastChannelStorage {
    private val values = mutableMapOf<String, Long>()

    override fun read(key: String): Long? = values[key]

    override fun write(key: String, channelId: Long) {
        values[key] = channelId
    }
}

/**
 * The channel each profile watched last on this device: «Continue» on the TV page. Kept per server
 * and profile, so the profiles of a shared TV don't see each other's channel. Nothing goes to the
 * server.
 */
class LastChannels(private val storage: LastChannelStorage) {
    private val flows = mutableMapOf<String, MutableStateFlow<Long?>>()

    /** The last channel of a profile, updated while the app runs. */
    fun of(serverId: String, userId: Long): LastChannel = LastChannel(this, key(serverId, userId))

    internal fun flow(key: String): MutableStateFlow<Long?> = synchronized(flows) {
        flows.getOrPut(key) { MutableStateFlow(storage.read(key)) }
    }

    internal fun write(key: String, channelId: Long) {
        val flow = flow(key)
        if (flow.value == channelId) return
        storage.write(key, channelId)
        flow.value = channelId
    }

    companion object {
        fun key(serverId: String, userId: Long): String = "last_channel/$serverId/$userId"
    }
}

/** One profile's last channel (see [LastChannels.of]). */
class LastChannel internal constructor(private val owner: LastChannels, private val key: String) {
    /** The channel's id, or null before the profile watched one here. */
    val id: StateFlow<Long?> get() = owner.flow(key).asStateFlow()

    /** The player shows [channelId]: it becomes «Continue». */
    fun remember(channelId: Long) = owner.write(key, channelId)
}
