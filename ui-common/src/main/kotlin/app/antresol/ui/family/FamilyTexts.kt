package app.antresol.ui.family

import android.content.res.Resources
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.Chapter
import app.antresol.core.model.FamilyMember
import app.antresol.core.playback.Chapters
import app.antresol.ui.R
import app.antresol.ui.format.Durations
import kotlin.math.roundToInt

/** The words of «Семья» (the web's FamilyProgress `placeOf` and `jumpLabel`): where a profile is, and what continuing says. */
object FamilyTexts {
    /** «С2 Э5», «Спецвыпуск 1». */
    fun code(resources: Resources, m: FamilyMember): String =
        if (m.season == 0) resources.getString(R.string.family_episode_special, m.episode) else resources.getString(R.string.family_episode_code, m.season, m.episode)

    /** «Том 3» (or the volume's own title); 3.5 stays «3.5». */
    fun volume(resources: Resources, m: FamilyMember): String =
        if (m.volume > 0) resources.getString(R.string.family_volume, number(m.volume)) else m.title

    private fun number(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    private fun percent(fraction: Double): Int = (fraction.coerceIn(0.0, 1.0) * 100).roundToInt()

    /** «34 мин», «1 ч 50 мин»; "" under half a minute (the caller then shows a clock). */
    fun minutes(resources: Resources, ms: Long): String {
        val rounded = (ms + 30_000) / 60_000
        return if (rounded <= 0) "" else Durations.spoken(resources, rounded * 60_000)
    }

    private fun page(resources: Resources, m: FamilyMember): String = when {
        m.page <= 0 -> ""
        m.pages > 0 -> resources.getString(R.string.family_page_of, m.page, m.pages)
        else -> resources.getString(R.string.family_page, m.page)
    }

    /** Where [m] is: «С2 Э5 · 34 мин», «стр. 120 из 300», «Глава 7 · 42 %», «Просмотрено». */
    fun placeOf(resources: Resources, kind: FamilyKind, m: FamilyMember, chapters: List<Chapter> = emptyList()): String {
        if (m.finished) {
            return resources.getString(
                when (kind) {
                    FamilyKind.Series -> R.string.family_all_watched
                    FamilyKind.Book, FamilyKind.Comic -> R.string.family_read
                    FamilyKind.Audiobook -> R.string.family_heard
                    FamilyKind.Movie, FamilyKind.Episode -> R.string.family_watched
                },
            )
        }
        return when (kind) {
            FamilyKind.Series ->
                if (m.next) resources.getString(R.string.family_next, code(resources, m))
                else listOf(code(resources, m), minutes(resources, m.positionMs)).filter { it.isNotEmpty() }.joinToString(" · ")
            FamilyKind.Movie, FamilyKind.Episode ->
                if (m.durationMs > 0) {
                    resources.getString(R.string.family_time_of, minutes(resources, m.positionMs).ifEmpty { Durations.clock(m.positionMs) }, minutes(resources, m.durationMs))
                } else {
                    minutes(resources, m.positionMs)
                }
            FamilyKind.Audiobook -> {
                val share = resources.getString(R.string.family_percent, percent(m.fraction))
                if (chapters.isEmpty()) share else "${resources.getString(R.string.family_chapter, Chapters.indexAt(chapters, m.positionMs) + 1)} · $share"
            }
            FamilyKind.Comic ->
                if (m.next) resources.getString(R.string.family_next, volume(resources, m))
                else listOf(volume(resources, m), page(resources, m)).filter { it.isNotEmpty() }.joinToString(" · ")
            FamilyKind.Book -> page(resources, m).ifEmpty { resources.getString(R.string.family_percent, percent(m.fraction)) }
        }
    }

    /** What continuing from [m] says («Смотреть с 17:00»), or "" when there is nothing to continue (finished). */
    fun jumpLabel(resources: Resources, kind: FamilyKind, m: FamilyMember): String {
        if (m.finished) return ""
        val at = Durations.clock(m.positionMs)
        return when (kind) {
            FamilyKind.Series ->
                if (m.next || m.positionMs <= 0) resources.getString(R.string.family_watch_episode, code(resources, m))
                else resources.getString(R.string.family_watch_episode_at, code(resources, m), at)
            FamilyKind.Movie, FamilyKind.Episode -> if (m.positionMs > 0) resources.getString(R.string.family_watch_at, at) else ""
            FamilyKind.Audiobook -> if (m.positionMs > 0) resources.getString(R.string.family_listen_at, at) else ""
            FamilyKind.Comic -> when {
                m.next -> resources.getString(R.string.family_read_volume, volume(resources, m))
                m.page > 0 -> resources.getString(R.string.family_read_page, m.page)
                else -> resources.getString(R.string.family_read_volume, volume(resources, m))
            }
            FamilyKind.Book -> when {
                m.page > 0 -> resources.getString(R.string.family_read_page, m.page)
                m.locator.isNotEmpty() -> resources.getString(R.string.family_read_at, percent(m.fraction))
                else -> ""
            }
        }
    }

    /**
     * What a row of the start prompt says: «Дарья · 1:12:40», and for another episode than the one that starts
     * «Дарья · С1 Э5 · 12:40». The name is never declined, so it stays the first word.
     */
    fun resumeRow(resources: Resources, m: FamilyMember, startingItemId: Long): String =
        listOfNotNull(m.displayName, code(resources, m).takeIf { m.itemId != startingItemId && m.episode > 0 }, Durations.clock(m.positionMs))
            .joinToString(" · ")

    /** The folded line's words: «Alex · С2 Э5 · 34 мин» (the most recent profile). */
    fun summary(resources: Resources, kind: FamilyKind, members: List<FamilyMember>, chapters: List<Chapter> = emptyList()): String {
        val first = members.firstOrNull() ?: return ""
        return "${first.name} · ${placeOf(resources, kind, first, chapters)}"
    }

    /** The accessible name of one profile's continue button: «Смотреть с 17:00 — с места профиля «Alex»». */
    fun jumpDescription(resources: Resources, label: String, m: FamilyMember): String =
        resources.getString(R.string.family_jump_description, label, m.name)
}
