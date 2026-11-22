package app.antresol.ui.ways

import android.content.Context
import android.content.SharedPreferences
import app.antresol.core.ways.WayDefaults

/**
 * [WayDefaults] in the device's preferences: per title (kind and TMDB id) the way its «Play» runs, and when it
 * was chosen. Stays on this device, never sent; the oldest entries go once there are more than [MAX_ENTRIES].
 */
class DeviceWayDefaults(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : WayDefaults {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(kind: String, tmdbId: Long): String? =
        prefs.getString(key(kind, tmdbId), null)?.substringBeforeLast(SEP)?.takeIf { it.isNotBlank() }

    override fun set(kind: String, tmdbId: Long, wayKey: String) {
        val edit = prefs.edit().putString(key(kind, tmdbId), "$wayKey$SEP${clock()}")
        val all = prefs.all
        if (all.size >= MAX_ENTRIES) {
            // Oldest first: by the time each entry was written.
            all.entries.sortedBy { (it.value as? String)?.substringAfterLast(SEP, "")?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES + 1)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    private fun key(kind: String, tmdbId: Long) = "$kind/$tmdbId"

    companion object {
        private const val FILE = "way_defaults"
        private const val SEP = '|'

        /** How many titles are remembered. */
        const val MAX_ENTRIES = 300
    }
}
