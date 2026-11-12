package app.antresol.ui.playback

import android.content.Context
import android.content.SharedPreferences
import app.antresol.core.channels.ExternalTrackMemory
import app.antresol.core.channels.LiveTrackChoice
import app.antresol.core.channels.SubtitleChoice
import app.antresol.core.model.ProgramTrack

/**
 * [ExternalTrackMemory] in the device's preferences: per external channel, the audio's language +
 * label and the subtitles' (or «off»). It stays on this device and is never sent to the server.
 */
class DeviceExternalTrackMemory(context: Context) : ExternalTrackMemory {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(channelId: Long): LiveTrackChoice? {
        val p = "$channelId."
        val audio = prefs.getString(p + AUDIO_LABEL, null)?.let { ProgramTrack(name = it, label = it, lang = prefs.getString(p + AUDIO_LANG, "").orEmpty()) }
        val subtitles = when (prefs.getString(p + SUBS, null)) {
            OFF -> SubtitleChoice.Off
            ON -> prefs.getString(p + SUBS_LABEL, null)?.let {
                SubtitleChoice.On(ProgramTrack(name = it, label = it, lang = prefs.getString(p + SUBS_LANG, "").orEmpty()))
            }
            else -> null
        }
        if (audio == null && subtitles == null) return null
        return LiveTrackChoice(audio, subtitles)
    }

    override fun put(channelId: Long, choice: LiveTrackChoice) {
        val p = "$channelId."
        prefs.edit().apply {
            val audio = choice.audio
            if (audio == null) {
                remove(p + AUDIO_LABEL)
                remove(p + AUDIO_LANG)
            } else {
                putString(p + AUDIO_LABEL, audio.name)
                putString(p + AUDIO_LANG, audio.lang)
            }
            when (val subtitles = choice.subtitles) {
                null -> remove(p + SUBS)
                SubtitleChoice.Off -> putString(p + SUBS, OFF)
                is SubtitleChoice.On -> {
                    putString(p + SUBS, ON)
                    putString(p + SUBS_LABEL, subtitles.track.name)
                    putString(p + SUBS_LANG, subtitles.track.lang)
                }
            }
        }.apply()
    }

    private companion object {
        const val FILE = "live_external_tracks"
        const val AUDIO_LABEL = "audio.label"
        const val AUDIO_LANG = "audio.lang"
        const val SUBS = "subs"
        const val SUBS_LABEL = "subs.label"
        const val SUBS_LANG = "subs.lang"
        const val OFF = "off"
        const val ON = "on"
    }
}
