package app.antresol.tv.homechannels

import app.antresol.core.model.Kind
import java.net.URI
import java.security.MessageDigest

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
 * The deep links of the TV app: `antresol://play/{id}?u={userId}&n={nonce}` and
 * `antresol://title/{kind}/{id}?u={userId}&n={nonce}`.
 *
 * The activity is exported, so any app on the device can start it with such an address. [nonce] is a random string
 * of this install ([HomeChannelPrefs.linkNonce]) that only the program intents given to the TV provider carry; a link
 * without it is not ours and [parse] returns null. A shell reaches the app the same way only when it knows the nonce
 * (README: `adb shell run-as` on a debuggable build reads it).
 */
object HomeLinks {
    const val SCHEME = "antresol"
    const val HOST_PLAY = "play"
    const val HOST_TITLE = "title"

    private const val PARAM_USER = "u="
    private const val PARAM_NONCE = "n="

    /** The address of [link]; with [nonce] (empty: none) as the last query part. */
    fun uri(link: HomeLink, nonce: String = ""): String {
        val base = when (link) {
            is HomeLink.Play -> "$SCHEME://$HOST_PLAY/${link.itemId}?$PARAM_USER${link.userId}"
            is HomeLink.Open -> "$SCHEME://$HOST_TITLE/${link.kind}/${link.itemId}?$PARAM_USER${link.userId}"
        }
        return if (nonce.isEmpty()) base else "$base&$PARAM_NONCE$nonce"
    }

    /** Whether [given] is [expected]: compared in constant time, and never true for an empty [expected]. */
    fun nonceMatches(expected: String, given: String?): Boolean =
        expected.isNotEmpty() && given != null &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))

    /**
     * The link in [uri], or null for anything else (another scheme, a malformed or unknown path, no profile, and a
     * nonce that is not [nonce]: an address that did not come from one of our tiles).
     */
    fun parse(uri: String?, nonce: String): HomeLink? {
        val parsed = try {
            URI(uri ?: return null)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (parsed.scheme != SCHEME) return null
        val segments = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = parsed.rawQuery.orEmpty().split('&')
        if (!nonceMatches(nonce, query.firstOrNull { it.startsWith(PARAM_NONCE) }?.removePrefix(PARAM_NONCE))) return null
        val userId = query.firstOrNull { it.startsWith(PARAM_USER) }?.removePrefix(PARAM_USER)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
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
 * a link that came while nobody was signed in must not fire minutes later, when somebody signs in. This is about
 * staleness only (the time is taken when the activity receives the link); that the link is ours is the nonce's job.
 */
data class DeepLinkRequest(val link: HomeLink, val atMs: Long) {
    fun fresh(nowMs: Long): Boolean = nowMs - atMs in 0..MAX_AGE_MS

    companion object {
        /** Long enough for a cold start that restores the session first. */
        const val MAX_AGE_MS = 60_000L
    }
}
