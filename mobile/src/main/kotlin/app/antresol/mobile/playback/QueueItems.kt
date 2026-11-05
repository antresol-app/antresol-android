package app.antresol.mobile.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.antresol.core.api.MediaUrls
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Track
import app.antresol.core.playback.AudiobookTimeline

/** Extras carried in [MediaMetadata.extras] of every queue entry. */
object QueueExtras {
    const val DURATION_MS = "antresol.durationMs"
    const val STARRED = "antresol.starred"
    const val ALBUM_ID = "antresol.albumId"
    const val HAS_LYRICS = "antresol.hasLyrics"

    /** Audiobook parts: where the part starts on the whole-book timeline, and the book's length. */
    const val PART_START_MS = "antresol.partStartMs"
    const val BOOK_DURATION_MS = "antresol.bookDurationMs"

    /**
     * Audiobook parts: false when some part length is unknown (files not probed yet). The whole-book
     * positions are then wrong, so no progress is written for the book.
     */
    const val REPORTS_PROGRESS = "antresol.reportsProgress"
}

/** Builds Media3 queue entries for tracks and audiobook parts, and their stream URIs. */
object QueueItems {
    fun track(track: Track, urls: MediaUrls): MediaItem {
        val extras = Bundle().apply {
            putLong(QueueExtras.DURATION_MS, track.durationMs)
            putBoolean(QueueExtras.STARRED, track.starred)
            putLong(QueueExtras.ALBUM_ID, track.albumId)
            putBoolean(QueueExtras.HAS_LYRICS, track.hasLyrics)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setTrackNumber(track.trackNo.takeIf { it > 0 })
            .setArtworkUri(urls.image(track.cover)?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaRef.Track(track.id).encode())
            .setMediaMetadata(metadata)
            .build()
    }

    /** One entry per part, in order, all carrying the book's title, author and cover. */
    fun audiobook(book: BookDetail, urls: MediaUrls): List<MediaItem> {
        val timeline = AudiobookTimeline.of(book.files, book.durationMs)
        val total = book.durationMs.takeIf { it > 0 } ?: timeline.totalMs
        val author = book.authors.joinToString(", ")
        val artwork = urls.image(book.card.poster)?.let(Uri::parse)
        return book.files.mapIndexed { index, file ->
            val extras = Bundle().apply {
                putLong(QueueExtras.DURATION_MS, file.durationMs)
                putLong(QueueExtras.PART_START_MS, timeline.partStartMs(index))
                putLong(QueueExtras.BOOK_DURATION_MS, total)
                putBoolean(QueueExtras.REPORTS_PROGRESS, timeline.isComplete)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(book.card.title)
                .setArtist(author.ifBlank { book.narrator })
                .setAlbumTitle(book.card.title)
                .setArtworkUri(artwork)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build()
            MediaItem.Builder()
                .setMediaId(MediaRef.BookPart(book.card.id, index, file.id).encode())
                .setMediaMetadata(metadata)
                .build()
        }
    }

    /**
     * The stream URL for [ref]. No `mk`: the service streams through the shared OkHttp client,
     * which adds the Bearer header for the server's origin. [startSec] starts a transcoded stream
     * later (such streams cannot seek).
     */
    fun streamUri(ref: MediaRef, urls: MediaUrls, startSec: Double? = null): String = when (ref) {
        is MediaRef.Track -> urls.track(ref.trackId, startSec = startSec)
        is MediaRef.BookPart -> urls.audio(ref.fileId, startSec = startSec)
    }

    /** Re-attaches the stream URI to an entry that crossed the session boundary without it. */
    fun withUri(item: MediaItem, urls: MediaUrls): MediaItem? {
        val ref = MediaRef.parse(item.mediaId) ?: return null
        return item.buildUpon().setUri(streamUri(ref, urls)).build()
    }
}
