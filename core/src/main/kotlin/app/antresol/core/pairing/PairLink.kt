package app.antresol.core.pairing

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The 6-character code of a TV sign-in request (contract §2.2). */
object PairCode {
    /** The server's alphabet: no 0/O, 1/I/L. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    /** Upper-cases [input] and drops spaces and dashes; null unless 6 characters of [ALPHABET] remain. */
    fun normalize(input: String): String? {
        val code = input.filterNot { it.isWhitespace() || it == '-' || it == '‐' || it == '–' }.uppercase()
        return code.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** `ABC234` → `ABC 234`, as the TV shows it. */
    fun display(code: String): String =
        if (code.length == LENGTH) code.substring(0, 3) + " " + code.substring(3) else code
}

internal fun normalizedCode(code: String): String = PairCode.normalize(code) ?: code.uppercase()

/**
 * A scanned QR of a TV sign-in: `<origin>/pair?code=ABC234&sid=<serverId>` (contract §2.5).
 * The host is ignored on purpose: the phone always calls its own signed-in server.
 */
data class PairLink(val code: String, val serverId: String?) {
    /** False when the link names another server than [ownServerId] ("this TV belongs to another server"). */
    fun belongsTo(ownServerId: String): Boolean = serverId == null || serverId.equals(ownServerId, ignoreCase = true)

    companion object {
        /**
         * Accepts an http(s) URL whose path is `/pair` with a valid `code`, or a bare code typed or
         * scanned as text. Returns null for anything else (another QR code).
         */
        fun parse(text: String): PairLink? {
            val trimmed = text.trim()
            PairCode.normalize(trimmed)?.let { return PairLink(it, null) }
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (!url.encodedPath.trimEnd('/').endsWith("/pair")) return null
            val code = url.queryParameter("code")?.let(PairCode::normalize) ?: return null
            val sid = url.queryParameter("sid")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            return PairLink(code, sid)
        }
    }
}
