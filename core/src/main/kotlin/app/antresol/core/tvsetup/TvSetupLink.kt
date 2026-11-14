package app.antresol.core.tvsetup

import app.antresol.core.pairing.PairLink
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * The QR of a TV that has no server yet («TV setup by phone»): `http://<tv-lan-ip>:<port>/setup?n=<nonce>&v=1`.
 * The phone app POSTs its server to [setupUrl]; a phone camera without the app opens the same URL
 * and gets the TV's HTML form.
 *
 * Only plain http to a private or link-local IPv4 literal is accepted: the TV listens on its LAN
 * address, and a QR pointing anywhere else is not a TV of this home.
 */
data class TvSetupLink(val host: String, val port: Int, val nonce: String) {
    /** Where the phone POSTs: `http://host:port/setup`. */
    val setupUrl: HttpUrl
        get() = HttpUrl.Builder().scheme("http").host(host).port(port).addPathSegment("setup").build()

    /** The QR content, exactly as the TV shows it. */
    override fun toString(): String = "http://$host:$port/setup?n=$nonce&v=$VERSION"

    companion object {
        const val VERSION = "1"

        /**
         * Returns the link when [text] is a TV setup URL of protocol version 1, else null.
         * [allowLoopback] is for tests on one machine (a QR never carries a loopback address).
         */
        fun parse(text: String, allowLoopback: Boolean = false): TvSetupLink? {
            val url = text.trim().toHttpUrlOrNull() ?: return null
            if (url.scheme != "http") return null
            if (url.encodedPath.trimEnd('/') != "/setup") return null
            val version = url.queryParameter("v")
            if (version != null && version != VERSION) return null
            val nonce = url.queryParameter("n")?.takeIf(SetupNonces::looksLikeNonce) ?: return null
            if (!LocalAddresses.isIpv4Literal(url.host)) return null
            val address = runCatching { InetAddress.getByName(url.host) }.getOrNull() ?: return null
            if (!LocalAddresses.isPrivate(address, allowLoopback)) return null
            return TvSetupLink(url.host, url.port, nonce)
        }
    }
}

/** What the phone's scanner read: a sign-in code of a TV that has a server, or a TV to set up. */
sealed interface ScannedTvCode {
    data class SignIn(val link: PairLink) : ScannedTvCode

    data class Setup(val link: TvSetupLink) : ScannedTvCode

    companion object {
        /** The two QR kinds of the contract, or null for anything else. */
        fun parse(text: String, allowLoopback: Boolean = false): ScannedTvCode? =
            TvSetupLink.parse(text, allowLoopback)?.let(::Setup) ?: PairLink.parse(text)?.let(::SignIn)
    }
}
