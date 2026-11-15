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

/** Trailers of a detail page (`GET /api/items/{id}/trailers`) and opening them in the YouTube app. */
object Trailers {
    /** Kinds whose detail page offers trailers (a season or episode gets its series'). */
    val KINDS = setOf(Kind.MOVIE, Kind.SERIES, Kind.SEASON, Kind.EPISODE)

    /**
     * The item's trailers, best first; empty for a [kind] without trailers and on any failure (the
     * button just stays hidden).
     */
    suspend fun fetch(client: AntresolClient, id: Long, kind: String? = null): List<Trailer> {
        if (kind != null && kind !in KINDS) return emptyList()
        return try {
            client.trailers(id).trailers.filter { it.key.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Opens [trailer] in the YouTube app (`vnd.youtube:<key>`), else its https page in whatever
     * handles it. Returns false when nothing on the device can open either.
     */
    fun open(context: Context, trailer: Trailer): Boolean =
        start(context, trailer.appUrl) || start(context, trailer.webUrl)

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
