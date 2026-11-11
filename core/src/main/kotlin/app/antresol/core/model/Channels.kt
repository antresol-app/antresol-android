package app.antresol.core.model

import kotlinx.serialization.Serializable

/**
 * A live channel of `GET /api/channels`: a never-ending programme made of library titles. Its
 * identity is its [number] and [name] (the name comes in the viewer's language); [category] is only
 * a filter ("theme"). [favorite] is per profile, on the server. [now]/[next] may be absent. The
 * editor fields (`rules`, `canEdit`) and the IPTV `logo` are ignored: the apps only watch channels.
 */
@Serializable
data class Channel(
    val id: Long,
    val number: Int = 0,
    val name: String = "",
    val category: String = "",
    val favorite: Boolean = false,
    val personal: Boolean = false,
    val auto: Boolean = false,
    val enabled: Boolean = true,
    val now: ChannelProgram? = null,
    val next: ChannelProgram? = null,
)

/**
 * One programme on a channel: a movie or an episode ([subtitle] like "S1 E5 · Episode 5"). Times are
 * ISO 8601. [audio]/[subtitles] name the live stream's renditions really present in this programme
 * (null = unknown, empty = none).
 */
@Serializable
data class ChannelProgram(
    val id: String = "",
    val itemId: Long = 0,
    val kind: String = "",
    val title: String = "",
    val subtitle: String = "",
    val overview: String = "",
    val year: Int? = null,
    val rating: String = "",
    val genres: List<String> = emptyList(),
    val start: String = "",
    val end: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val external: Boolean = false,
    val audio: List<ProgramTrack>? = null,
    val subtitles: List<ProgramTrack>? = null,
)

/**
 * An audio or subtitle rendition of a programme: [slot] (`ru`, `ru2`, `en`, `orig`; subtitles `ru`,
 * `en`), [name] = the HLS rendition's `NAME` to select, [label] for the picker («Русский — LostFilm»),
 * [lang] its language.
 */
@Serializable
data class ProgramTrack(
    val slot: String = "",
    val name: String = "",
    val label: String = "",
    val lang: String = "",
)

@Serializable
data class ChannelList(
    val channels: List<Channel> = emptyList(),
    /** The channel this profile tuned in last (any device), or null. */
    val lastChannel: Long? = null,
    val canManage: Boolean = false,
    val canCreate: Boolean = false,
)

/** `GET /api/channels/{id}/guide`: the programme on air and the ones after it. */
@Serializable
data class ChannelGuide(
    val channel: Channel,
    val programs: List<ChannelProgram> = emptyList(),
)

/** `GET /api/channels/epg`: every channel (list order) with the programmes overlapping `[from, to)`. */
@Serializable
data class ChannelEpg(
    val from: String = "",
    val to: String = "",
    val channels: List<ChannelEpgRow> = emptyList(),
)

@Serializable
data class ChannelEpgRow(
    val id: Long,
    val number: Int = 0,
    val name: String = "",
    val category: String = "",
    val favorite: Boolean = false,
    val programs: List<ChannelProgram> = emptyList(),
)

/** `PUT /api/channels/last`. */
@Serializable
data class LastChannelRequest(val channelId: Long)

/**
 * `GET /api/me/playback-prefs`: the profile's languages. [subMode] is `off`, `always` or `foreign`
 * (subtitles when the audio is not in [audioLangs]).
 */
@Serializable
data class PlaybackPrefs(
    val audioLangs: List<String> = emptyList(),
    val subMode: String = "off",
    val subLangs: List<String> = emptyList(),
    val preferCleaned: Boolean = false,
) {
    companion object {
        const val SUB_OFF = "off"
        const val SUB_ALWAYS = "always"
        const val SUB_FOREIGN = "foreign"
    }
}
