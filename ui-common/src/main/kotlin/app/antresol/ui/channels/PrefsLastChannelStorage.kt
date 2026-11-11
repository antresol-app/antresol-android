package app.antresol.ui.channels

import android.content.Context
import app.antresol.core.channels.LastChannelStorage
import app.antresol.core.channels.LastChannels

/** [LastChannelStorage] in the app's private SharedPreferences (read synchronously, like the settings). */
class PrefsLastChannelStorage(context: Context) : LastChannelStorage {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun write(key: String, channelId: Long) {
        prefs.edit().putLong(key, channelId).apply()
    }

    private companion object {
        const val FILE = "last_channels"
    }
}

/** The app's [LastChannels], kept in its SharedPreferences. */
fun lastChannels(context: Context): LastChannels = LastChannels(PrefsLastChannelStorage(context))
