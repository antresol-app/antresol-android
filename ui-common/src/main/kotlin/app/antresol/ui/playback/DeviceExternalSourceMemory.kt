package app.antresol.ui.playback

import android.content.Context
import android.content.SharedPreferences
import app.antresol.core.channels.ExternalSourceMemory

/**
 * [ExternalSourceMemory] in the device's preferences: per external channel, a digest of each source
 * URL that played badly (never the URL: a provider's may carry credentials) and when. A mark holds
 * for [LIFETIME_MS]; the player tries those sources last. Stays on this device, never sent.
 */
class DeviceExternalSourceMemory(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExternalSourceMemory {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun isBad(channelId: Long, url: String): Boolean {
        val at = prefs.getLong(key(channelId, url), 0L)
        return at > 0 && clock() - at < LIFETIME_MS
    }

    override fun markBad(channelId: Long, url: String) {
        prefs.edit().putLong(key(channelId, url), clock()).apply()
    }

    override fun markGood(channelId: Long, url: String) {
        val key = key(channelId, url)
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    private fun key(channelId: Long, url: String) = "$channelId.${digest(url)}"

    companion object {
        private const val FILE = "live_external_bad_sources"

        /** How long a bad mark holds. */
        const val LIFETIME_MS = 3L * 24 * 3600 * 1000

        /** FNV-1a 64 of the URL, hex: stable, not reversible to the URL. */
        fun digest(url: String): String {
            var hash = -0x340d631b7bdddcdbL
            for (byte in url.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(hash)
        }
    }
}
