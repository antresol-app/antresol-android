package app.antresol.core.playback

import app.antresol.core.model.Chapter

/** Chapter navigation on the whole-book timeline of an audiobook (`Chapter.startMs` is book time). */
object Chapters {
    /** Going back within this much of a chapter's start jumps to the previous chapter instead. */
    const val RESTART_THRESHOLD_MS = 3_000L

    /** Index of the chapter playing at [bookMs] (the last one starting at or before it), or -1 without chapters. */
    fun indexAt(chapters: List<Chapter>, bookMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val index = chapters.indexOfLast { it.startMs <= bookMs }
        return index.coerceAtLeast(0)
    }

    /** Where "previous chapter" goes: the start of the current chapter, or the previous one near the start. */
    fun previousStartMs(chapters: List<Chapter>, bookMs: Long): Long {
        val index = indexAt(chapters, bookMs)
        if (index < 0) return 0
        val start = chapters[index].startMs
        return if (bookMs - start > RESTART_THRESHOLD_MS || index == 0) start else chapters[index - 1].startMs
    }

    /** The start of the next chapter, or null in the last one. */
    fun nextStartMs(chapters: List<Chapter>, bookMs: Long): Long? {
        val index = indexAt(chapters, bookMs)
        return chapters.getOrNull(index + 1)?.startMs
    }

    /** Where the chapter playing at [bookMs] ends: its `endMs`, the next start, or [bookDurationMs]. */
    fun endMs(chapters: List<Chapter>, bookMs: Long, bookDurationMs: Long): Long {
        val index = indexAt(chapters, bookMs)
        if (index < 0) return bookDurationMs
        val chapter = chapters[index]
        return chapter.endMs.takeIf { it > chapter.startMs }
            ?: chapters.getOrNull(index + 1)?.startMs
            ?: bookDurationMs
    }
}
