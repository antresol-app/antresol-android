package app.antresol.core.model

import app.antresol.core.channels.Channels
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
    /**
     * The channel's logo: the uploaded one (`/api/channels/{id}/logo.png?v=N`), or an external
     * channel's provider logo (absolute); null when there is none.
     */
    val logoUrl: String? = null,
    /** A channel of an external IPTV source: the app plays [stream] itself (the own live route 404s). */
    val external: Boolean = false,
    /** Where an [external] channel's video is, null for Antresol's own channels. */
    val stream: ChannelStream? = null,
)

/**
 * An external channel's stream: the provider's URL ([url]; or the server's hidden-address form
 * `/api/channels/live/ext/{id}/{n}`, a 302 that needs the media key), the HTTP headers the
 * playlist asked for, the [fallbacks] to try in order when it does not open, and the platforms it
 * is known not to play on ([unsupported], e.g. `web`).
 */
@Serializable
data class ChannelStream(
    val url: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
    val fallbacks: List<StreamSource> = emptyList(),
    val unsupported: List<String> = emptyList(),
) {
    /** The primary stream and its fallbacks, in the order they are tried. */
    val sources: List<StreamSource>
        get() = listOf(StreamSource(url, userAgent, referrer, unsupported)) + fallbacks
}

/** One URL of an external channel with its headers. */
@Serializable
data class StreamSource(
    val url: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
    val unsupported: List<String> = emptyList(),
)

/**
 * One programme on a channel: a movie or an episode ([subtitle] like "S1 E5 · Episode 5"), or a
 * programme of an external channel's XMLTV guide (`kind` "external", [itemId] 0: no item page; see
 * [isLibraryItem]). Times are ISO 8601. [audio]/[subtitles] name the live stream's renditions really present in this programme
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
) {
    /** The programme is a library title with its own page (not an external guide entry). */
    val isLibraryItem: Boolean
        get() = itemId > 0 && kind != KIND_EXTERNAL

    /**
     * [start] and [end] as epoch milliseconds, or null when they cannot be read. Parsed once: the
     * guide asks for them for every programme on every pass.
     */
    val startMs: Long? by lazy(LazyThreadSafetyMode.PUBLICATION) { Channels.epochMs(start) }
    val endMs: Long? by lazy(LazyThreadSafetyMode.PUBLICATION) { Channels.epochMs(end) }

    companion object {
        const val KIND_EXTERNAL = "external"
    }
}

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
    /** A cleaned voice-over: the `name` of the voice-over it is the cleaned version of (null: none). */
    val cleanedOf: String? = null,
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
    /** The uploaded logo when the server sends it with the guide; else taken from the channel list. */
    val logoUrl: String? = null,
    val external: Boolean = false,
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

/**
 * `GET/PUT /api/me/ui-language`: the profile's interface language. [language] is the profile's own
 * choice ("" = none: «как у профиля»), [effective] what the apps show ("ru", "en", or "" to follow
 * the device).
 */
@Serializable
data class UiLanguage(
    val language: String = "",
    val effective: String = "",
)

@Serializable
data class UiLanguageRequest(val language: String)
