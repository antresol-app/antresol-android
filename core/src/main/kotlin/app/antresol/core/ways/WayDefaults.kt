package app.antresol.core.ways

import app.antresol.core.model.AddonWay

/**
 * The way each title plays with its «Play» button: the one the viewer last chose for it (kept on this
 * device only), else the first the addons offer. A way is named by [AddonWay.key], a title by its kind and
 * TMDB id; the series' episodes follow their series.
 */
interface WayDefaults {
    /** The [AddonWay.key] remembered for the title, or null. */
    fun get(kind: String, tmdbId: Long): String?

    fun set(kind: String, tmdbId: Long, wayKey: String)
}

/** [WayDefaults] that forgets with the process (tests, and screens without a store). */
class MemoryWayDefaults : WayDefaults {
    private val map = HashMap<String, String>()

    override fun get(kind: String, tmdbId: Long): String? = synchronized(map) { map[key(kind, tmdbId)] }

    override fun set(kind: String, tmdbId: Long, wayKey: String) {
        synchronized(map) { map[key(kind, tmdbId)] = wayKey }
    }

    private fun key(kind: String, tmdbId: Long) = "$kind/$tmdbId"
}

/**
 * The way «Play» runs among [this]: the one [rememberedKey] names while the addons still offer it, else the
 * first. Null when there is no way at all.
 */
fun List<AddonWay>.defaultWay(rememberedKey: String?): AddonWay? =
    firstOrNull { rememberedKey != null && it.key == rememberedKey } ?: firstOrNull()
