package app.antresol.ui.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * The ExoPlayer of the apps' music and audiobook playback: music audio focus, paused when
 * headphones are unplugged, awake on the network while it plays. Streams use the signed-in
 * client's OkHttp (Bearer header): [httpClient] is asked for every request, so a profile switch
 * (new token) applies from the next one. [seekBackMs] and [seekForwardMs] are what the session's
 * seek-back and seek-forward commands move by.
 */
@OptIn(UnstableApi::class)
fun buildAudioPlayer(context: Context, seekBackMs: Long, seekForwardMs: Long, httpClient: () -> OkHttpClient?): ExoPlayer {
    val fallbackHttp by lazy { OkHttpClient() }
    val calls = Call.Factory { request -> (httpClient() ?: fallbackHttp).newCall(request) }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(calls))))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(seekBackMs)
        .setSeekForwardIncrementMs(seekForwardMs)
        .build()
}
