package app.antresol.core.tvsetup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The one nonce of the TV setup QR: 128 random bits, base64url without padding (22 characters),
 * valid for [lifetimeMs] and used once. Issuing a new one replaces the old one.
 *
 * A POST first [claim]s the nonce (a second POST meanwhile gets "expired"), then either
 * [consume]s it (setup done) or [release]s it (the address was wrong: the person may try again
 * from the same page).
 */
class SetupNonces(
    private val now: () -> Long = System::currentTimeMillis,
    private val lifetimeMs: Long = LIFETIME_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    private var current: String? = null
    private var issuedAt = 0L
    private var claimed = false

    /** A fresh nonce; the previous one stops working. */
    @Synchronized
    fun issue(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        current = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        issuedAt = now()
        claimed = false
        return current!!
    }

    /** When the current nonce stops working (milliseconds on [now]'s clock), or null. */
    @Synchronized
    fun expiresAt(): Long? = current?.let { issuedAt + lifetimeMs }

    /** True when [nonce] is the current, unexpired, unclaimed nonce (the GET page). */
    @Synchronized
    fun isValid(nonce: String?): Boolean = matches(nonce) && !claimed

    /** Takes the nonce for one POST; false when it is wrong, expired, used or busy. */
    @Synchronized
    fun claim(nonce: String?): Boolean {
        if (!matches(nonce) || claimed) return false
        claimed = true
        return true
    }

    /** The POST failed before the TV took a server: the nonce works again. */
    @Synchronized
    fun release(nonce: String) {
        if (matches(nonce)) claimed = false
    }

    /** Setup done: the nonce never works again. */
    @Synchronized
    fun consume(nonce: String) {
        if (matches(nonce)) current = null
    }

    private fun matches(nonce: String?): Boolean {
        val expected = current ?: return false
        if (nonce == null || now() - issuedAt >= lifetimeMs) return false
        return MessageDigest.isEqual(expected.toByteArray(), nonce.toByteArray())
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")

        /** Shape check for a scanned or posted nonce (not whether it is valid). */
        fun looksLikeNonce(text: String): Boolean = NONCE.matches(text)
    }
}
