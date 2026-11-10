package app.antresol.core.model

import kotlinx.serialization.Serializable

/**
 * A live channel of `GET /api/channels`: a never-ending programme made of library titles. [logo] is
 * the index (0…6) of the avatar gradient its logo tile is drawn in. [now]/[next] may be absent.
 * The editor fields (`rules`, `canEdit`) are ignored: the apps only watch channels.
 */
@Serializable
data class Channel(
    val id: Long,
    val number: Int = 0,
    val name: String = "",
    val category: String = "",
    val personal: Boolean = false,
    val auto: Boolean = false,
    val enabled: Boolean = true,
    val logo: Int = 0,
    val now: ChannelProgram? = null,
    val next: ChannelProgram? = null,
)

/** One programme on a channel: a movie or an episode ([subtitle] like "S1 E5 · Episode 5"). Times are ISO 8601. */
@Serializable
data class ChannelProgram(
    val itemId: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val overview: String = "",
    val year: Int? = null,
    val start: String = "",
    val end: String = "",
    val poster: String = "",
    val backdrop: String = "",
)

@Serializable
data class ChannelList(
    val channels: List<Channel> = emptyList(),
    val canManage: Boolean = false,
    val canCreate: Boolean = false,
)

/** `GET /api/channels/{id}/guide`: the programme on air and the ones after it. */
@Serializable
data class ChannelGuide(
    val channel: Channel,
    val programs: List<ChannelProgram> = emptyList(),
)
