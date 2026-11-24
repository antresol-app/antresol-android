package app.antresol.ui.trailers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Kind
import app.antresol.core.model.Trailer
import kotlinx.coroutines.CancellationException

/**
 * Trailers of a detail page (`GET /api/items/{id}/trailers`) and how each one plays (see [howToPlay]):
 * in the app's own player when the server fetches it, else in the embedded YouTube player, and in
 * the YouTube app only on a device without a WebView.
 */
object Trailers {
    /**
     * The item's trailers, best first and at most [MAX_TRAILERS] (the web offers the same few); empty
     * for a [kind] without trailers and on any failure (the button just stays hidden).
     */
    suspend fun fetch(client: AntresolClient, id: Long, kind: String? = null): List<Trailer> {
        if (kind != null && kind !in Kind.VIDEO) return emptyList()
        return try {
            client.trailers(id).trailers.filter { it.key.isNotBlank() }.take(MAX_TRAILERS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The best few trailers a page offers, as the web's «Ещё» menu does. */
    const val MAX_TRAILERS = 3

    /** Where a trailer plays. */
    enum class Mode {
        /** The server fetches it (`play`): the app's own video player. */
        Player,

        /** No `play` (public servers, trailers off): the official embedded player in a WebView. */
        Embed,

        /** No WebView on the device: the YouTube app or the browser ([open]). */
        External,
    }

    fun howToPlay(trailer: Trailer): Mode = when {
        trailer.playsInApp -> Mode.Player
        TrailerEmbed.isValidKey(trailer.key) && TrailerEmbed.isAvailable() -> Mode.Embed
        else -> Mode.External
    }

    /**
     * Opens [trailer] in the YouTube app (`vnd.youtube:<key>`), else its https page in whatever
     * handles it. Returns false when nothing on the device can open either.
     */
    fun open(context: Context, trailer: Trailer): Boolean = outsideLinks(trailer).any { start(context, it) }

    /** An https link to YouTube's own hosts; the path has no backslash or `@`, which parsers read differently. */
    private val YOUTUBE_PAGE = Regex("""https://(?:www\.youtube\.com|youtube\.com|youtu\.be)/[!-~&&[^\\@]]*""")

    /**
     * What [open] tries, in order: the YouTube app, then a YouTube page. The server's data is not
     * trusted to name anything else (`tel:`, `market:`, another site): a key that is not a video id
     * gives none, and a page link that is not YouTube's is replaced by the one built from the key.
     */
    internal fun outsideLinks(trailer: Trailer): List<String> {
        if (!TrailerEmbed.isValidKey(trailer.key)) return emptyList()
        val page = trailer.webUrl.takeIf { YOUTUBE_PAGE.matches(it) } ?: "https://www.youtube.com/watch?v=${trailer.key}"
        return listOf(trailer.appUrl, page)
    }

    private fun start(context: Context, uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/** The chooser's line for [trailer]: its TMDB name, else "Trailer"/"Teaser". */
fun trailerLabel(resources: android.content.res.Resources, trailer: Trailer): String = trailer.name.ifBlank {
    resources.getString(if (trailer.type.equals("Teaser", ignoreCase = true)) app.antresol.ui.R.string.trailer_teaser else app.antresol.ui.R.string.action_trailer)
}
