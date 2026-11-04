package app.antresol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/items/{id}` for movie, series, season and episode ids: card fields plus detail. */
@Serializable
data class ItemDetail(
    override val id: Long,
    override val kind: String = "",
    override val title: String = "",
    override val subtitle: String = "",
    override val year: Int? = null,
    override val poster: String = "",
    override val backdrop: String = "",
    override val logo: String = "",
    override val progress: Double = 0.0,
    override val badges: List<String> = emptyList(),
    override val seriesId: Long? = null,
    val originalTitle: String? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    /** Minutes. */
    val runtime: Int? = null,
    val rating: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonInfo> = emptyList(),
    val files: List<FileInfo> = emptyList(),
    val next: EpisodeCard? = null,
    val inWatchlist: Boolean = false,
    val actions: ItemActions = ItemActions(),
    val tmdbId: Long? = null,
) : CardFields

@Serializable
data class CastMember(
    val name: String,
    val character: String? = null,
)

@Serializable
data class ItemActions(
    val play: Boolean = false,
    val toServer: Boolean = false,
    val toDevice: Boolean = false,
    val watchNow: Boolean = false,
)

@Serializable
data class SeasonInfo(
    val id: Long,
    val number: Int = 0,
    val title: String = "",
    val episodes: List<EpisodeCard> = emptyList(),
)

/** An episode inside [SeasonInfo] or [ItemDetail.next]. Has no file ids; fetch the episode detail to play. */
@Serializable
data class EpisodeCard(
    override val id: Long,
    override val kind: String = Kind.EPISODE,
    override val title: String = "",
    override val subtitle: String = "",
    override val year: Int? = null,
    override val poster: String = "",
    override val backdrop: String = "",
    override val logo: String = "",
    override val progress: Double = 0.0,
    override val badges: List<String> = emptyList(),
    override val seriesId: Long? = null,
    val seasonNo: Int = 0,
    val episodeNo: Int = 0,
    val overview: String? = null,
    val durationMs: Long? = null,
    val watched: Boolean = false,
) : CardFields

/** A physical file of a movie or episode. [id] is a file id for `/api/play/{fileId}`. */
@Serializable
data class FileInfo(
    val id: Long,
    val name: String = "",
    val size: Long = 0,
    val durationMs: Long? = null,
    val resolution: String? = null,
    val audio: List<AudioTrack> = emptyList(),
    val subs: List<SubTrack> = emptyList(),
)

@Serializable
data class AudioTrack(
    /** ffprobe stream index; pass as `audioIndex` to `/api/play`. */
    val index: Int,
    val codec: String = "",
    /** ISO 639-2 code, `""` or `"und"` when unknown. */
    val lang: String = "",
    val title: String = "",
    val channels: Int = 0,
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class SubTrack(
    /** Stream index; external sidecar files use 1000+. */
    val index: Int,
    val codec: String = "",
    val lang: String = "",
    val title: String = "",
    val forced: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
    val external: Boolean = false,
    val burnIn: Boolean = false,
    /** Server-relative WebVTT URL; absent for image subtitles. Needs the Bearer header. */
    val url: String? = null,
)
