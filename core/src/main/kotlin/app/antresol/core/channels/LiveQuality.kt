package app.antresol.core.channels

import app.antresol.core.model.Quality
import app.antresol.core.model.QualityInfo

/**
 * One row of the live player's «Качество» menu: a [Quality] value ([Quality.AUTO], [Quality.ORIGINAL]
 * or a height such as "720"), whether it is on, and for «Авто» the quality the server applies
 * ([applied]: "720" shows «Авто · 720p»; null when it is the original or unknown).
 */
data class LiveQualityOption(val value: String, val selected: Boolean, val applied: String? = null)

/** The choices of the live player's quality menu. */
object LiveQuality {
    /**
     * Our channel: the film player's choices ([Quality.ALL]); «Авто» names what the server applies
     * ([info] of `/api/channels/live/quality`) unless that is the original, as the web player does.
     */
    fun own(selected: String, info: QualityInfo?): List<LiveQualityOption> {
        val current = selected.takeIf { it in Quality.ALL } ?: Quality.AUTO
        val applied = info?.applied?.takeIf { current == Quality.AUTO && it.isNotBlank() && it != Quality.ORIGINAL && it != Quality.AUTO }
        return Quality.ALL.map { value ->
            LiveQualityOption(value, value == current, applied.takeIf { value == Quality.AUTO })
        }
    }

    /**
     * An external stream: «Авто» and its own video variants by height, highest first, only when its
     * master has several heights (one variant: no menu, an empty list). [maxHeight] is the height the
     * player is limited to (null: auto); a limit that matches no variant shows «Авто».
     */
    fun external(heights: List<Int>, maxHeight: Int?): List<LiveQualityOption> {
        val distinct = heights.filter { it > 0 }.distinct().sortedDescending()
        if (distinct.size < 2) return emptyList()
        val chosen = maxHeight?.takeIf { it in distinct }
        return listOf(LiveQualityOption(Quality.AUTO, chosen == null)) +
            distinct.map { LiveQualityOption(it.toString(), it == chosen) }
    }

    /** The height limit for an external choice: null for «Авто» (or anything that is not a height). */
    fun externalMaxHeight(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }
}
