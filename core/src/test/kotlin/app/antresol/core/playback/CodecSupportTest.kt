package app.antresol.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecSupportTest {
    @Test
    fun minimalDeviceGetsBaseline() {
        val caps = CodecSupport.caps(decoderMimes = setOf("video/avc", "audio/mp4a-latm"))
        assertEquals(listOf("h264"), caps.videoCodecs)
        assertEquals(listOf("aac", "mp3", "opus", "flac"), caps.audioCodecs)
        assertEquals(listOf("mp4", "mkv", "webm"), caps.containers)
    }

    @Test
    fun decodersAndPassthroughAddCodecs() {
        val caps = CodecSupport.caps(
            decoderMimes = setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01", "audio/ac3"),
            passthrough = setOf("eac3"),
        )
        assertEquals(listOf("h264", "hevc", "vp9", "av1"), caps.videoCodecs)
        assertEquals(listOf("aac", "mp3", "opus", "flac", "ac3", "eac3"), caps.audioCodecs)
    }

    @Test
    fun failingDecodersLowerTheCapsOnce() {
        val caps = CodecSupport.caps(setOf("video/hevc", "audio/eac3"), passthrough = setOf("ac3"), maxHeight = 2160)
        val video = CodecSupport.withoutFailingVideo(caps)!!
        assertEquals(listOf("h264"), video.videoCodecs)
        assertEquals(1080, video.maxHeight)
        assertEquals(null, CodecSupport.withoutFailingVideo(video))
        val audio = CodecSupport.withoutFailingAudio(caps)!!
        assertEquals(CodecSupport.ALWAYS_AUDIO, audio.audioCodecs)
        assertEquals(null, CodecSupport.withoutFailingAudio(audio))
    }
}
