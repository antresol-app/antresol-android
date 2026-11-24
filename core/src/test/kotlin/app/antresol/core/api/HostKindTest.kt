package app.antresol.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKindTest {
    @Test
    fun homeAndLocalHostsArePrivate() {
        for (host in listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.3.4",
            "100.64.0.1", "100.127.255.1", "0.0.0.0",
            "::1", "::", "fe80::1", "febf::2", "fd12:3456::1", "fc00::9",
            "nas", "NAS", "nas.local", "media.lan", "box.home.arpa", "server.internal", "app.localhost", "nas.local.",
        )) {
            assertTrue(host, HostKind.isPrivate(host))
        }
    }

    @Test
    fun theInternetIsNotPrivate() {
        for (host in listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "192.169.0.1", "11.0.0.1",
            "2001:db8::1", "fe7f::1", "fec0::1", "fc::1",
            "media.example.com", "example.com", "locally.example.org", "my.local.example.com",
        )) {
            assertFalse(host, HostKind.isPrivate(host))
        }
    }

    @Test
    fun onlyPlainHttpOutsideTheHomeNetworkIsClearOverTheInternet() {
        // As typed: a bare host may end up on http (a bare host outside the home network is tried over https first).
        for (address in listOf("media.example.com", "media.example.com:8420", "http://media.example.com", " http://8.8.8.8:8420/ ", "http://[2001:db8::1]:8420")) {
            assertTrue(address, HostKind.isClearOverInternet(address))
        }
        for (address in listOf(
            "", "  ", "192.168.1.10", "192.168.1.10:8420", "http://nas.local:8420", "nas", "http://[fe80::1]:8420",
            "https://media.example.com", "HTTPS://media.example.com:8443", "ftp://media.example.com", "http://", "ex ample.com",
        )) {
            assertFalse(address, HostKind.isClearOverInternet(address))
        }
    }

    @Test
    fun aBareHostOutsideTheHomeNetworkIsTriedOverHttpsFirst() {
        assertEquals("https://media.example.com", HostKind.httpsFirst("media.example.com"))
        assertEquals("https://media.example.com", HostKind.httpsFirst(" media.example.com/ "))
        assertEquals("https://media.example.com/antresol", HostKind.httpsFirst("media.example.com/antresol"))
        assertEquals("https://8.8.8.8", HostKind.httpsFirst("8.8.8.8"))
    }

    @Test
    fun anythingTheUserChoseOrAtHomeKeepsTheDefaultAddress() {
        for (address in listOf(
            "media.example.com:8420", "media.example.com:443", "http://media.example.com", "https://media.example.com",
            "nas.local", "nas", "192.168.1.5", "localhost", "", "ex ample.com",
        )) {
            assertNull(address, HostKind.httpsFirst(address))
        }
    }
}
