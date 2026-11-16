package app.antresol.ui.trailers

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Trailers of servers that do not play them themselves (no `play`: public servers, trailers off):
 * the official YouTube IFrame player in a full-screen WebView inside the app.
 *
 * YouTube refuses embeds without a referrer (player error 153), so the page is loaded with
 * `loadDataWithBaseURL` under [ORIGIN] (the WebView then sends it as the Referer) and the player
 * is told the same `origin`. The iframe itself comes from the privacy-enhanced host [EMBED_HOST].
 */
object TrailerEmbed {
    /** The page's base URL and the player's `origin`. */
    const val ORIGIN = "https://www.youtube.com"

    /** Where the IFrame API builds the player iframe: `<host>/embed/<key>?autoplay=1&playsinline=1&rel=0…`. */
    const val EMBED_HOST = "https://www.youtube-nocookie.com"

    /** The name of the page's bridge to [TrailerEmbedState]. */
    const val BRIDGE = "AntresolTrailer"

    /** Video ids are short URL-safe base64; anything else never reaches the page. */
    private val KEY = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    /**
     * Whether the device has a working WebView (some TVs ship without one): without it the
     * trailer opens outside, in the YouTube app or the browser ([Trailers.open]).
     */
    fun isAvailable(): Boolean = try {
        CookieManager.getInstance()
        true
    } catch (e: Throwable) {
        false
    }

    /** The embed URL the player iframe loads (also what [html] asks the IFrame API for). */
    fun embedUrl(key: String): String = "$EMBED_HOST/embed/$key?autoplay=1&playsinline=1&rel=0"

    /**
     * The player page for [key] (a valid key, see [isValidKey]): the IFrame API fills the page,
     * starts playing at once and reports playing, ended and errors to [BRIDGE]; [TOGGLE_JS]
     * pauses or resumes it.
     */
    fun html(key: String): String {
        require(isValidKey(key)) { "Invalid trailer key" }
        return """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
            </head><body>
            <div id="player"></div>
            <script>
            var player;
            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                host: '$EMBED_HOST',
                videoId: '$key',
                width: '100%', height: '100%',
                playerVars: { autoplay: 1, playsinline: 1, rel: 0, origin: '$ORIGIN' },
                events: {
                  onReady: function (e) { e.target.playVideo(); },
                  onStateChange: function (e) { $BRIDGE.onState(e.data); },
                  onError: function (e) { $BRIDGE.onError(e.data); }
                }
              });
            }
            function antresolToggle() {
              if (!player || !player.getPlayerState) return;
              if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo();
            }
            </script>
            <script src="$ORIGIN/iframe_api"></script>
            </body></html>
        """.trimIndent()
    }

    /** Pauses a playing trailer, resumes a paused one. */
    const val TOGGLE_JS = "antresolToggle()"

    /** Pauses the trailer (the app left the screen); the viewer resumes it. */
    const val PAUSE_JS = "if (window.player && player.pauseVideo) player.pauseVideo()"
}

/** What the embedded trailer player shows. */
sealed interface EmbedPhase {
    data object Loading : EmbedPhase
    data object Playing : EmbedPhase

    /** The player reported an error (its numeric code), or the page did not load. */
    data class Failed(val code: Int) : EmbedPhase

    /** Played to the end: the screen closes. */
    data object Finished : EmbedPhase
}

/** The state of one embedded trailer, fed by the page's bridge; [reload] after an error. */
class TrailerEmbedState(val key: String) {
    var phase: EmbedPhase by mutableStateOf(if (TrailerEmbed.isValidKey(key)) EmbedPhase.Loading else EmbedPhase.Failed(INVALID_KEY))
        internal set

    internal var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    /** Play/pause through the IFrame API (TV: OK on the remote). */
    fun toggle() {
        webView?.evaluateJavascript(TrailerEmbed.TOGGLE_JS, null)
    }

    /** The app went to the background: the player is paused and the WebView stops its work. */
    internal fun onStop() {
        val view = webView ?: return
        view.evaluateJavascript(TrailerEmbed.PAUSE_JS, null)
        view.onPause()
    }

    /** Back in front: the WebView works again; the trailer stays paused until the viewer resumes it. */
    internal fun onStart() {
        webView?.onResume()
    }

    fun reload() {
        if (!TrailerEmbed.isValidKey(key)) return
        phase = EmbedPhase.Loading
        webView?.let(::load)
    }

    internal fun load(view: WebView) {
        view.loadDataWithBaseURL(TrailerEmbed.ORIGIN + "/", TrailerEmbed.html(key), "text/html", "utf-8", null)
    }

    /** The page's bridge; WebView calls it on a background thread. */
    internal val bridge: Any = Bridge()

    private inner class Bridge {
        @JavascriptInterface
        fun onState(state: Int) {
            main.post {
                when (state) {
                    STATE_PLAYING -> phase = EmbedPhase.Playing
                    STATE_ENDED -> phase = EmbedPhase.Finished
                }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            main.post { phase = EmbedPhase.Failed(code) }
        }
    }

    private companion object {
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1
        const val INVALID_KEY = -1
    }
}

/**
 * The full-screen WebView of [state]: JavaScript on, autoplay without a tap, black behind the
 * picture. It never takes focus (the screen's own keys drive it), is paused while the app is in the
 * background (a WebView left alone keeps playing its sound) and is destroyed with the screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerEmbedView(state: TrailerEmbedState, modifier: Modifier = Modifier) {
    LifecycleStartEffect(state) {
        state.onStart()
        onStopOrDispose { state.onStop() }
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        // Only the page itself failing counts (no network); a failed ad or image does not.
                        if (request.isForMainFrame || request.url.toString().endsWith("/iframe_api")) {
                            state.phase = EmbedPhase.Failed(NETWORK_ERROR)
                        }
                    }
                }
                addJavascriptInterface(state.bridge, TrailerEmbed.BRIDGE)
                state.webView = this
                if (state.phase == EmbedPhase.Loading) state.load(this)
            }
        },
        onRelease = { view ->
            if (state.webView === view) state.webView = null
            view.stopLoading()
            view.removeJavascriptInterface(TrailerEmbed.BRIDGE)
            view.destroy()
        },
        modifier = modifier,
    )
}

private const val NETWORK_ERROR = -2
