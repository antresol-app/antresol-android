package app.antresol.tv.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Makes transcoded audio seekable. When the server transcodes a track or audiobook part
 * (`X-Vault-Transcode: 1`) the stream has no Range support, so a seek reloads the item with
 * `&t=<seconds>` ([startingAt]) and this player adds that offset to every position it reports.
 * Original files seek normally. Everyone (UI, media session, progress reporting) goes through
 * this player, so the offset is invisible to them.
 */
internal class TranscodeSeekingPlayer(
    private val exo: ExoPlayer,
    /** The known length of an item from the server (track or part duration), if any. */
    private val knownDurationMs: (MediaItem) -> Long?,
    /** The same item streaming from [Long] ms, for a stream that cannot seek. */
    private val startingAt: (MediaItem, Long) -> MediaItem,
) : ForwardingPlayer(exo) {
    /** The media id currently loaded from an offset, its offset, and the original item. */
    private var offsetMediaId: String? = null
    private var offsetMs = 0L
    private var original: MediaItem? = null

    /** A start position asked for before the stream was prepared; applied once it is known to be unseekable. */
    private var pending: Pair<Int, Long>? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != offsetMediaId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) restoreOriginal()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) applyPending()
            }
        })
    }

    /** Starts the playlist at [index]/[positionMs]; call after `setMediaItems` with the same start. */
    fun expectStart(index: Int, positionMs: Long) {
        clearOffset()
        pending = if (positionMs > 0) index to positionMs else null
    }

    private fun currentOffset(): Long = if (exo.currentMediaItem?.mediaId == offsetMediaId) offsetMs else 0L

    override fun getCurrentPosition(): Long = exo.currentPosition + currentOffset()

    override fun getContentPosition(): Long = exo.contentPosition + currentOffset()

    override fun getBufferedPosition(): Long = exo.bufferedPosition + currentOffset()

    override fun getDuration(): Long {
        val item = exo.currentMediaItem ?: return C.TIME_UNSET
        val known = knownDurationMs(item)
        val offset = currentOffset()
        return when {
            offset > 0 -> known ?: exo.duration.takeIf { it != C.TIME_UNSET }?.plus(offset) ?: C.TIME_UNSET
            exo.duration != C.TIME_UNSET -> exo.duration
            else -> known ?: C.TIME_UNSET
        }
    }

    override fun getContentDuration(): Long = duration

    /** Every item can be sought: unseekable streams are reloaded from the target. */
    override fun isCurrentMediaItemSeekable(): Boolean = exo.currentMediaItem != null

    override fun seekTo(positionMs: Long) = seekTo(exo.currentMediaItemIndex, positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex != exo.currentMediaItemIndex) {
            restoreOriginal()
            exo.seekTo(mediaItemIndex, positionMs)
            pending = if (positionMs > 0) mediaItemIndex to positionMs else null
            return
        }
        val target = positionMs.coerceAtLeast(0)
        when {
            currentOffset() == 0L && streamIsSeekable() -> exo.seekTo(target)
            !streamIsKnown() -> {
                exo.seekTo(target)
                pending = mediaItemIndex to target
            }
            else -> reload(mediaItemIndex, target)
        }
    }

    override fun seekBack() = seekTo((currentPosition - exo.seekBackIncrement).coerceAtLeast(0))

    override fun seekForward() = seekTo(currentPosition + exo.seekForwardIncrement)

    override fun seekToPrevious() {
        if (exo.hasPreviousMediaItem() && currentPosition <= exo.maxSeekToPreviousPosition) {
            seekToPreviousMediaItem()
        } else {
            seekTo(0)
        }
    }

    override fun seekToPreviousMediaItem() {
        restoreOriginal()
        exo.seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        restoreOriginal()
        exo.seekToNextMediaItem()
    }

    override fun seekToNext() {
        restoreOriginal()
        exo.seekToNext()
    }

    private fun streamIsKnown(): Boolean {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return false
        return !timeline.getWindow(exo.currentMediaItemIndex, Timeline.Window()).isPlaceholder
    }

    private fun streamIsSeekable(): Boolean = streamIsKnown() && exo.isCurrentMediaItemSeekable

    private fun applyPending() {
        val (index, positionMs) = pending ?: return
        pending = null
        if (index != exo.currentMediaItemIndex || exo.isCurrentMediaItemSeekable) return
        if (abs(exo.currentPosition + currentOffset() - positionMs) > POSITION_TOLERANCE_MS) reload(index, positionMs)
    }

    /** Replaces the item with one streaming from [positionMs] (or the original for 0). */
    private fun reload(index: Int, positionMs: Long) {
        val item = exo.getMediaItemAt(index)
        val base = original?.takeIf { item.mediaId == offsetMediaId } ?: item
        if (positionMs <= 0) {
            clearOffset()
            exo.replaceMediaItem(index, base)
        } else {
            original = base
            offsetMediaId = base.mediaId
            offsetMs = positionMs
            exo.replaceMediaItem(index, startingAt(base, positionMs))
        }
        exo.seekTo(index, 0)
    }

    /** Puts the original item back once playback left the one loaded from an offset. */
    private fun restoreOriginal() {
        val base = original ?: return
        val id = offsetMediaId
        clearOffset()
        val index = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == id } ?: return
        exo.replaceMediaItem(index, base)
    }

    private fun clearOffset() {
        original = null
        offsetMediaId = null
        offsetMs = 0
    }

    private companion object {
        const val POSITION_TOLERANCE_MS = 1_500L
    }
}
