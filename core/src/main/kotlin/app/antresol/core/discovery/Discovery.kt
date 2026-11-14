package app.antresol.core.discovery

import app.antresol.core.api.AntresolClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * LAN discovery of Antresol servers (server contract «LAN discovery and TV sign-in by QR», §1):
 * a UDP broadcast probe ([UdpProbeSource]) and mDNS/DNS-SD `_antresol._tcp` (Android's NsdManager,
 * in `:ui-common`). Both report [ServerSighting]s; [ServerDiscovery] merges them by server id and
 * confirms every address with `GET /api/health` before it is shown.
 */
object DiscoveryProtocol {
    /** The UDP port of the probe (the same number as the HTTP port). */
    const val PORT = 8420

    /** The DNS-SD service type (domain `local.`). */
    const val SERVICE_TYPE = "_antresol._tcp"

    /** The probe datagram: 17 ASCII bytes. */
    val PROBE: ByteArray = "antresol-discover".toByteArray(Charsets.US_ASCII)

    /** The highest answer size the server sends. */
    const val MAX_ANSWER_BYTES = 1200

    private val SERVER_ID = Regex("^[0-9a-f]{32}$")

    fun isServerId(value: String): Boolean = SERVER_ID.matches(value)

    /**
     * Parses a probe answer from [source]. The first candidate is `<scheme>://<source>:<port>`
     * (inside Docker the server's own `urls` are container addresses), then the answer's `urls`.
     * Returns null for anything that is not an Antresol answer.
     */
    fun parseUdpAnswer(data: ByteArray, length: Int = data.size, source: InetAddress): ServerSighting? {
        if (length <= 0 || length > data.size) return null
        val answer = try {
            AntresolClient.json.decodeFromString(UdpAnswer.serializer(), String(data, 0, length, Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (answer.service != "antresol") return null
        val id = answer.id.lowercase()
        if (!isServerId(id)) return null
        val scheme = answer.scheme.takeIf { it == "http" || it == "https" } ?: "http"
        val port = answer.port.takeIf { it in 1..65535 } ?: PORT
        val fromSource = baseUrl(scheme, source, port)
        val listed = answer.urls.mapNotNull(::cleanUrl)
        return ServerSighting(
            id = id,
            name = answer.name.trim(),
            version = answer.version.trim(),
            candidates = (listOfNotNull(fromSource) + listed).distinct(),
            via = Via.Udp,
        )
    }

    /**
     * Parses a resolved DNS-SD service: [txt] its TXT attributes (values may be null for flags),
     * [hosts] its resolved addresses (only IPv4 is used), [srvPort] the SRV port.
     */
    fun parseMdns(txt: Map<String, ByteArray?>, hosts: List<InetAddress>, srvPort: Int): ServerSighting? {
        fun value(key: String): String? = txt.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)?.trim()
        val id = value("id")?.lowercase() ?: return null
        if (!isServerId(id)) return null
        val scheme = value("scheme")?.takeIf { it == "http" || it == "https" } ?: "http"
        val port = srvPort.takeIf { it in 1..65535 } ?: value("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: PORT
        val candidates = hosts.filterIsInstance<Inet4Address>().mapNotNull { baseUrl(scheme, it, port) }.distinct()
        if (candidates.isEmpty()) return null
        return ServerSighting(
            id = id,
            name = value("name").orEmpty(),
            version = value("version").orEmpty(),
            candidates = candidates,
            via = Via.Mdns,
        )
    }

    private fun baseUrl(scheme: String, host: InetAddress, port: Int): String? = try {
        HttpUrl.Builder().scheme(scheme).host(host.hostAddress.substringBefore('%')).port(port).build()
            .toString().trimEnd('/')
    } catch (_: IllegalArgumentException) {
        null
    }

    /** An absolute http(s) URL from the answer, without a trailing slash; anything else is dropped. */
    private fun cleanUrl(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        return parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    @Serializable
    private data class UdpAnswer(
        val service: String = "",
        val protocol: Int = 0,
        val id: String = "",
        val name: String = "",
        val version: String = "",
        val scheme: String = "http",
        val port: Int = PORT,
        val urls: List<String> = emptyList(),
    )
}

/** How a server was seen. */
enum class Via { Udp, Mdns }

/** A server seen on the network, not yet confirmed with `/api/health`. [candidates] are base URLs, best first. */
data class ServerSighting(
    val id: String,
    val name: String,
    val version: String,
    val candidates: List<String>,
    val via: Via,
)

/** A confirmed server: its `/api/health` at [baseUrl] answered with [id]. */
data class DiscoveredServer(
    val id: String,
    val name: String,
    val version: String,
    val baseUrl: String,
) {
    /** `host:port` for the list (the scheme is http on a LAN). */
    val address: String
        get() = baseUrl.substringAfter("://").substringBefore('/')
}

/** What the "Connect to your server" screen shows: the confirmed servers, and whether the first search is running. */
data class DiscoveryState(
    val servers: List<DiscoveredServer> = emptyList(),
    val searching: Boolean = true,
)

/** Merges sightings of the same server id (seen by UDP and mDNS, or several answers). */
object Sightings {
    /** [next] wins for name/version (when not blank); candidates keep [previous]'s order, new ones appended. */
    fun merge(previous: ServerSighting?, next: ServerSighting): ServerSighting {
        if (previous == null) return next
        require(previous.id == next.id) { "Different servers" }
        // A UDP answer's source address is the most reliable (works through Docker's port mapping): keep it first.
        val ordered = if (next.via == Via.Udp && previous.via != Via.Udp) {
            next.candidates + previous.candidates
        } else {
            previous.candidates + next.candidates
        }
        return ServerSighting(
            id = next.id,
            name = next.name.ifBlank { previous.name },
            version = next.version.ifBlank { previous.version },
            candidates = ordered.distinct(),
            via = if (previous.via == Via.Udp || next.via == Via.Udp) Via.Udp else Via.Mdns,
        )
    }
}
