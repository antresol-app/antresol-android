package app.antresol.core.api

import app.antresol.core.tvsetup.LocalAddresses
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a server address points: on a home or local network, or out on the Internet. The apps talk
 * plain `http://` to servers at home (the usual setup, a bare host gets port 8420), which is fine on a
 * LAN but sends the password, the token and the media key in clear text anywhere else.
 */
object HostKind {
    private val PORT = Regex(""":\d+$""")
    private val IPV6_LINK_LOCAL = Regex("""^fe[89ab][0-9a-f]:""")
    private val IPV6_UNIQUE_LOCAL = Regex("""^f[cd][0-9a-f]{2}:""")
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".localhost", ".home.arpa", ".internal")

    /**
     * [host] (a name, or an address as [HttpUrl.host] gives it) is on a local network: loopback, private
     * (10/8, 172.16/12, 192.168/16), link-local, unique-local IPv6 (fc00::/7), the shared range
     * 100.64/10 (VPN overlays such as Tailscale), a single-label name (`nas`) or one under a local
     * suffix (`.local`, `.lan`, `.home.arpa`, `.internal`). Never asks DNS.
     */
    fun isPrivate(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        return when {
            name.isEmpty() -> true
            LocalAddresses.isIpv4Literal(name) -> privateIpv4(name.split('.').map(String::toInt))
            ':' in name -> name == "::" || name == "::1" || IPV6_LINK_LOCAL.containsMatchIn(name) || IPV6_UNIQUE_LOCAL.containsMatchIn(name)
            '.' !in name -> true
            else -> LOCAL_SUFFIXES.any(name::endsWith)
        }
    }

    private fun privateIpv4(o: List<Int>): Boolean = when (o[0]) {
        0, 10, 127 -> true
        100 -> o[1] in 64..127
        169 -> o[1] == 254
        172 -> o[1] in 16..31
        192 -> o[1] == 168
        else -> false
    }

    /**
     * [address], as typed, would reach a server outside the local network without https: its password
     * and tokens would cross the Internet in clear text. False for an address that is not valid yet.
     */
    fun isClearOverInternet(address: String): Boolean {
        val url = parse(address) ?: return false
        return url.scheme == "http" && !isPrivate(url.host)
    }

    /**
     * For a bare host outside the local network (no scheme, no port) the `https://` URL to try before
     * the default `http://host:8420`; null for everything else (the user chose the scheme or the port,
     * or the host is at home, where servers speak http).
     */
    fun httpsFirst(address: String): String? {
        val text = address.trim().trimEnd('/')
        if ("://" in text || PORT.containsMatchIn(text.substringBefore('/'))) return null
        val url = parse(text) ?: return null
        if (isPrivate(url.host)) return null
        return url.newBuilder().scheme("https").port(443).build().toString().trimEnd('/')
    }

    private fun parse(address: String): HttpUrl? {
        val text = address.trim().trimEnd('/')
        if (text.isEmpty()) return null
        return (if ("://" in text) text else "http://$text").toHttpUrlOrNull()
    }
}
