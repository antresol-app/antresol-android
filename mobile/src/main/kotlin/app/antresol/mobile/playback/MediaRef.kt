package app.antresol.mobile.playback

/**
 * What one queue entry of the audio service plays, encoded in the Media3 `mediaId` so the
 * service can rebuild the stream URL when a controller hands it media items (local
 * configuration such as the URI does not cross the session boundary).
 */
sealed interface MediaRef {
    /** A music track (`/api/tracks/{trackId}/stream`). */
    data class Track(val trackId: Long) : MediaRef

    /** Part [partIndex] (file [fileId]) of audiobook [bookId] (`/api/audio/{fileId}`). */
    data class BookPart(val bookId: Long, val partIndex: Int, val fileId: Long) : MediaRef

    fun encode(): String = when (this) {
        is Track -> "track:$trackId"
        is BookPart -> "book:$bookId:$partIndex:$fileId"
    }

    companion object {
        fun parse(mediaId: String?): MediaRef? {
            val parts = mediaId?.split(':') ?: return null
            return when (parts.firstOrNull()) {
                "track" -> parts.getOrNull(1)?.toLongOrNull()?.let(::Track)
                "book" -> {
                    if (parts.size != 4) return null
                    val bookId = parts[1].toLongOrNull() ?: return null
                    val index = parts[2].toIntOrNull() ?: return null
                    val fileId = parts[3].toLongOrNull() ?: return null
                    BookPart(bookId, index, fileId)
                }
                else -> null
            }
        }
    }
}
