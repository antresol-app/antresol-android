package app.antresol.core.playback

import app.antresol.core.model.SubTrack
import java.util.Locale

/** Picks the subtitle a viewer asked to turn on automatically by language. */
object SubtitleLanguages {
    /**
     * The text track in [iso1] (ISO 639-1, "" for none) to turn on when a video starts: only tracks
     * the client can side-load (with a `url`), full subtitles before forced ones.
     */
    fun preferred(tracks: List<SubTrack>, iso1: String): SubTrack? {
        if (iso1.isBlank()) return null
        return tracks
            .filter { it.url != null && sameLanguage(it.lang, iso1) }
            .minByOrNull { if (it.forced) 1 else 0 }
    }

    /**
     * Whether a track language code ("en", "eng", "rus", or a bibliographic "ger"/"fre" as
     * Matroska and ffprobe report them) is the language [iso1].
     */
    fun sameLanguage(code: String, iso1: String): Boolean {
        if (code.isBlank() || iso1.isBlank()) return false
        if (code.equals(iso1, ignoreCase = true)) return true
        val terminology = code.lowercase().let { BIBLIOGRAPHIC_TO_TERMINOLOGY[it] ?: it }
        return runCatching { Locale(terminology).isO3Language.equals(Locale(iso1).isO3Language, ignoreCase = true) }.getOrDefault(false)
    }

    /** ISO 639-2/B codes that differ from the /T codes Java's `isO3Language` returns. */
    private val BIBLIOGRAPHIC_TO_TERMINOLOGY = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
        "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
        "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
        "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )
}
