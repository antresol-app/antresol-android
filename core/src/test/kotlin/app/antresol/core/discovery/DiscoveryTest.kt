package app.antresol.core.discovery

import app.antresol.core.model.Health
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class DiscoveryTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val source = InetAddress.getByName("192.168.1.10")

    private fun answer(json: String) = json.toByteArray(Charsets.UTF_8)

    @Test
    fun udpAnswerPutsTheSourceAddressFirst() {
        val sighting = DiscoveryProtocol.parseUdpAnswer(
            answer(
                """{"service":"antresol","protocol":1,"id":"$id","name":"Home Media","version":"1.4.0","scheme":"http",
                   "port":8420,"urls":["http://172.17.0.2:8420","https://media.example.org/"]}""",
            ),
            source = source,
        )!!
        assertEquals(id, sighting.id)
        assertEquals("Home Media", sighting.name)
        assertEquals("1.4.0", sighting.version)
        assertEquals(
            listOf("http://192.168.1.10:8420", "http://172.17.0.2:8420", "https://media.example.org"),
            sighting.candidates,
        )
        assertEquals(Via.Udp, sighting.via)
    }

    @Test
    fun udpAnswerOfAnotherServiceOrGarbageIsIgnored() {
        assertNull(DiscoveryProtocol.parseUdpAnswer(answer("""{"service":"jellyfin","id":"$id"}"""), source = source))
        assertNull(DiscoveryProtocol.parseUdpAnswer(answer("""{"service":"antresol","id":"not-hex"}"""), source = source))
        assertNull(DiscoveryProtocol.parseUdpAnswer(answer("antresol-discover"), source = source))
        assertNull(DiscoveryProtocol.parseUdpAnswer(ByteArray(0), source = source))
    }

    @Test
    fun udpAnswerUnknownKeysAndDefaults() {
        val sighting = DiscoveryProtocol.parseUdpAnswer(
            answer("""{"service":"antresol","protocol":2,"id":"${id.uppercase()}","future":{"x":1},"port":9000,"urls":["ftp://x","nonsense"]}"""),
            source = source,
        )!!
        assertEquals(id, sighting.id)
        assertEquals(listOf("http://192.168.1.10:9000"), sighting.candidates)
    }

    @Test
    fun mdnsTxtGivesNameVersionAndIpv4Address() {
        val txt = mapOf(
            "txtvers" to "1".toByteArray(),
            "id" to id.toByteArray(),
            "name" to "Гостиная Antresol".toByteArray(Charsets.UTF_8),
            "version" to "1.4.0".toByteArray(),
            "scheme" to "http".toByteArray(),
            "port" to "8420".toByteArray(),
            "path" to "/".toByteArray(),
            "flag" to null,
        )
        val v6 = InetAddress.getByName("fe80::1")
        val sighting = DiscoveryProtocol.parseMdns(txt, listOf(v6, InetAddress.getByName("192.168.1.10")), 8420)!!
        assertEquals("Гостиная Antresol", sighting.name)
        assertEquals("1.4.0", sighting.version)
        assertEquals(listOf("http://192.168.1.10:8420"), sighting.candidates)
        assertEquals(Via.Mdns, sighting.via)
    }

    @Test
    fun mdnsWithoutIdOrIpv4IsIgnored() {
        assertNull(DiscoveryProtocol.parseMdns(mapOf("name" to "x".toByteArray()), listOf(source), 8420))
        assertNull(DiscoveryProtocol.parseMdns(mapOf("id" to id.toByteArray()), listOf(InetAddress.getByName("fe80::1")), 8420))
        // No SRV port: the TXT port, then the default.
        assertEquals(
            listOf("http://192.168.1.10:8420"),
            DiscoveryProtocol.parseMdns(mapOf("id" to id.toByteArray()), listOf(source), 0)!!.candidates,
        )
    }

    @Test
    fun mergeKeepsUdpSourceFirstAndUnionsCandidates() {
        val mdns = ServerSighting(id, "A", "1.3.0", listOf("http://192.168.1.11:8420"), Via.Mdns)
        val udp = ServerSighting(id, "", "1.4.0", listOf("http://192.168.1.10:8420", "http://192.168.1.11:8420"), Via.Udp)
        val merged = Sightings.merge(mdns, udp)
        assertEquals(listOf("http://192.168.1.10:8420", "http://192.168.1.11:8420"), merged.candidates)
        assertEquals("A", merged.name)
        assertEquals("1.4.0", merged.version)
        assertEquals(Via.Udp, merged.via)
        assertEquals(merged.candidates, Sightings.merge(merged, mdns).candidates)
    }

    @Test
    fun subnetBroadcast() {
        val ip = InetAddress.getByName("192.168.1.37") as Inet4Address
        assertEquals("192.168.1.255", UdpProbeSource.subnetBroadcast(ip, 24)!!.hostAddress)
        assertEquals("10.0.255.255", UdpProbeSource.subnetBroadcast(InetAddress.getByName("10.0.3.4") as Inet4Address, 16)!!.hostAddress)
        assertNull(UdpProbeSource.subnetBroadcast(ip, 32))
        assertTrue(UdpProbeSource.defaultBroadcastTargets().any { it.hostAddress == "255.255.255.255" })
    }

    /** A fake server on loopback answers the real probe over UDP. */
    @Test
    fun udpProbeFindsAServerOverTheSocket() = runBlocking {
        val responder = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val probes = mutableListOf<String>()
        val worker = thread(isDaemon = true) {
            val buffer = ByteArray(512)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    probes += String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val body = """{"service":"antresol","protocol":1,"id":"$id","name":"Loop","version":"1.4.0","scheme":"http","port":${responder.localPort},"urls":[]}"""
                        .toByteArray()
                    responder.send(DatagramPacket(body, body.size, packet.socketAddress))
                }
            } catch (_: Exception) {
            }
        }
        try {
            val probe = UdpProbeSource(
                port = responder.localPort,
                unicastHosts = { listOf("127.0.0.1") },
                broadcastTargets = { emptyList() },
                sends = 2,
                gapMs = 50,
                listenMs = 400,
            )
            val sighting = withTimeout(5_000) { probe.sightings().first() }
            assertEquals(id, sighting.id)
            assertEquals("Loop", sighting.name)
            assertEquals("http://127.0.0.1:${responder.localPort}", sighting.candidates.first())
            assertEquals("antresol-discover", probes.first())
        } finally {
            responder.close()
            worker.join(1_000)
        }
    }

    @Test
    fun discoveryConfirmsWithHealthAndMergesById() = runTest {
        val udp = DiscoverySource {
            flowOf(ServerSighting(id, "Home", "1.4.0", listOf("http://172.17.0.2:8420", "http://192.168.1.10:8420"), Via.Udp))
        }
        val mdns = DiscoverySource {
            flowOf(
                ServerSighting(id, "Home", "1.4.0", listOf("http://192.168.1.10:8420"), Via.Mdns),
                // An impostor: answers health with another id, never listed.
                ServerSighting("ffffffffffffffffffffffffffffffff", "Fake", "1", listOf("http://192.168.1.66:8420"), Via.Mdns),
            )
        }
        val checked = mutableListOf<String>()
        val discovery = ServerDiscovery(listOf(udp, mdns), health = { url ->
            checked += url
            when (url) {
                "http://192.168.1.10:8420" -> Health(ok = true, serverId = id, version = "1.4.1")
                "http://192.168.1.66:8420" -> Health(ok = true, serverId = id, version = "1")
                else -> throw java.io.IOException("unreachable")
            }
        }, searchWindowMs = 1_000)
        val states = discovery.states().take(3).toList()
        val last = states.last()
        assertFalse(last.searching)
        assertEquals(1, last.servers.size)
        val server = last.servers.single()
        assertEquals(DiscoveredServer(id, "Home", "1.4.1", "http://192.168.1.10:8420"), server)
        assertEquals("192.168.1.10:8420", server.address)
        assertEquals(1, checked.count { it == "http://192.168.1.10:8420" })
        assertTrue(states.first().searching)
    }

    @Test
    fun aServerSeenLaterWithANewAddressIsTriedAgain() = runTest {
        val second = CompletableDeferred<Unit>()
        val source = DiscoverySource {
            flow {
                emit(ServerSighting(id, "Home", "1.4.0", listOf("http://10.0.0.1:8420"), Via.Mdns))
                second.await()
                emit(ServerSighting(id, "Home", "1.4.0", listOf("http://10.0.0.2:8420"), Via.Udp))
            }
        }
        val discovery = ServerDiscovery(listOf(source), health = { url ->
            if (url.contains("10.0.0.2")) Health(ok = true, serverId = id) else null
        }, searchWindowMs = 10)
        val found = CompletableDeferred<DiscoveryState>()
        backgroundScope.launch {
            discovery.states().collect { state ->
                if (!state.searching && state.servers.isEmpty()) second.complete(Unit)
                if (state.servers.isNotEmpty()) found.complete(state)
            }
        }
        assertEquals("http://10.0.0.2:8420", found.await().servers.single().baseUrl)
    }
}
