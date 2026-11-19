package app.antresol.tv.homechannels

import app.antresol.core.model.Kind
import java.net.URI

/**
 * Where a tile on the TV's home screen leads inside the app. Built into the tile's intent
 * ([HomeLinks.uri]) and read back by `MainActivity` ([HomeLinks.parse]).
 *
 * [userId] is the profile that was signed in when the tile was published: the shell ignores a link of
 * another profile (its tiles are replaced within seconds of a profile change, a stale one is not acted on).
 */
sealed interface HomeLink {
    val userId: Long

    /** Play a movie or episode (a series or season: its next episode) from where this profile stopped. */
    data class Play(val itemId: Long, override val userId: Long) : HomeLink

    /** Open the page of a title. */
    data class Open(val kind: String, val itemId: Long, override val userId: Long) : HomeLink
}

/**
 * The deep links of the TV app: `antresol://play/{id}?u={userId}` and
 * `antresol://title/{kind}/{id}?u={userId}`. The same links work from a shell:
 * `adb shell am start -a android.intent.action.VIEW -d "antresol://play/19?u=1" app.antresol.mobile`.
 */
object HomeLinks {
    const val SCHEME = "antresol"
    const val HOST_PLAY = "play"
    const val HOST_TITLE = "title"

    fun uri(link: HomeLink): String = when (link) {
        is HomeLink.Play -> "$SCHEME://$HOST_PLAY/${link.itemId}?u=${link.userId}"
        is HomeLink.Open -> "$SCHEME://$HOST_TITLE/${link.kind}/${link.itemId}?u=${link.userId}"
    }

    /** The link in [uri], or null for anything else (another scheme, a malformed or unknown path, no profile). */
    fun parse(uri: String?): HomeLink? {
        val parsed = try {
            URI(uri ?: return null)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (parsed.scheme != SCHEME) return null
        val segments = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val userId = parsed.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("u=") }?.removePrefix("u=")?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return when (parsed.host) {
            HOST_PLAY -> segments.singleOrNull()?.toPositiveLong()?.let { HomeLink.Play(it, userId) }
            HOST_TITLE -> if (segments.size == 2 && segments[0] in Kind.VIDEO) {
                segments[1].toPositiveLong()?.let { HomeLink.Open(segments[0], it, userId) }
            } else {
                null
            }
            else -> null
        }
    }

    private fun String.toPositiveLong(): Long? = toLongOrNull()?.takeIf { it > 0 }
}

/**
 * A [HomeLink] the activity received, waiting for the signed-in shell to act on it. Dropped when old:
 * a link that came while nobody was signed in must not fire minutes later, when somebody signs in.
 */
data class DeepLinkRequest(val link: HomeLink, val atMs: Long) {
    fun fresh(nowMs: Long): Boolean = nowMs - atMs in 0..MAX_AGE_MS

    companion object {
        /** Long enough for a cold start that restores the session first. */
        const val MAX_AGE_MS = 60_000L
    }
}
