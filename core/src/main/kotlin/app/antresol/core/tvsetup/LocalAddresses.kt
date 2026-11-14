package app.antresol.core.tvsetup

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Home-network address rules of the TV setup listener. */
object LocalAddresses {
    private val IPV4_LITERAL = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    fun isIpv4Literal(host: String): Boolean = IPV4_LITERAL.matches(host)

    /**
     * True for private (10/8, 172.16/12, 192.168/16) and link-local (169.254/16, fe80::/10)
     * addresses; loopback only when [allowLoopback] (the TV itself, and tests).
     */
    fun isPrivate(address: InetAddress, allowLoopback: Boolean = true): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || (allowLoopback && address.isLoopbackAddress)

    /**
     * The TV's IPv4 address on the home network: the first private or link-local IPv4 of an
     * interface that is up (Ethernet and Wi-Fi before others, private before link-local). Null
     * when the TV has none (then it shows only the remote fallbacks).
     */
    fun lanIpv4(): Inet4Address? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name.orEmpty() to it } }
                .filter { (_, address) -> isPrivate(address, allowLoopback = false) }
        }.getOrDefault(emptyList())
        return candidates
            .sortedWith(
                compareBy(
                    { (_, address) -> if (address.isSiteLocalAddress) 0 else 1 },
                    { (name, _) -> if (name.startsWith("eth") || name.startsWith("wlan") || name.startsWith("en")) 0 else 1 },
                ),
            )
            .firstOrNull()?.second
    }
}
