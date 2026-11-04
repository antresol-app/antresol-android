package app.antresol.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.SubTrack
import app.antresol.ui.R
import java.util.Locale

/** Duration formatting. Clock strings are locale-neutral; spoken forms come from string resources. */
object Durations {
    /** `2:05`, `1:02:03` — for player positions and track lengths. */
    fun clock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** `1 h 52 min`, `45 min`, `2 h` (localized) — for runtimes and audiobook lengths. */
    fun spoken(resources: Resources, ms: Long): String {
        val totalMin = ((ms.coerceAtLeast(0) + 30_000) / 60_000).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> resources.getString(R.string.duration_hours_minutes, h, m)
            h > 0 -> resources.getString(R.string.duration_hours, h)
            else -> resources.getString(R.string.duration_minutes, m.coerceAtLeast(1))
        }
    }

    /** `45 min left` (localized). */
    fun remaining(resources: Resources, ms: Long): String =
        resources.getString(R.string.duration_left, spoken(resources, ms))
}

@Composable
fun spokenDuration(ms: Long): String = Durations.spoken(LocalContext.current.resources, ms)

@Composable
fun remainingDuration(ms: Long): String = Durations.remaining(LocalContext.current.resources, ms)

enum class BadgeKind { Quality, Info }

/** Card badges are server-localized strings ("1080p", "Audio EN RU"); this only classifies and orders them. */
object Badges {
    private val quality = Regex("""^(\d{3,4}p|4K|8K|HDR.*|Dolby Vision|Lossless.*|Hi-?Res.*)$""", RegexOption.IGNORE_CASE)

    fun kindOf(badge: String): BadgeKind = if (quality.matches(badge.trim())) BadgeKind.Quality else BadgeKind.Info

    fun ordered(badges: List<String>): List<String> = badges.sortedBy { if (kindOf(it) == BadgeKind.Quality) 0 else 1 }
}

/** Human labels for audio and subtitle tracks. */
object TrackLabels {
    /** Display name of an ISO 639-1/639-2 code in [locale], or null for `""`/`und`. */
    fun language(code: String, locale: Locale = Locale.getDefault()): String? {
        if (code.isBlank() || code.equals("und", ignoreCase = true)) return null
        val name = Locale(code).getDisplayLanguage(locale)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return code.uppercase(Locale.ROOT)
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    fun channels(count: Int): String? = when (count) {
        0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${count}ch"
    }

    fun audio(resources: Resources, track: AudioTrack, locale: Locale = Locale.getDefault()): String {
        if (track.title.isNotBlank()) return track.title
        val parts = listOfNotNull(language(track.lang, locale), track.codec.uppercase(Locale.ROOT).ifBlank { null }, channels(track.channels))
        return parts.joinToString(" · ").ifBlank { resources.getString(R.string.track_number, track.index) }
    }

    fun subtitle(resources: Resources, track: SubTrack, locale: Locale = Locale.getDefault()): String {
        val base = track.title.ifBlank { language(track.lang, locale) ?: resources.getString(R.string.track_number, track.index) }
        return if (track.forced) resources.getString(R.string.subtitle_forced, base) else base
    }
}
