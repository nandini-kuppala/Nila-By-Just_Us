package com.nila.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which address the two phones are told to use.
 *
 * The case that matters is the first one, and it is taken from the interface
 * list a real iQOO produces while sitting on ordinary Wi-Fi. Both of this app's
 * screens used to print 192.168.49.1 there -- the Wi-Fi Direct group owner,
 * which is up on most Qualcomm builds whether or not anything is using it, and
 * which on the receiving phone names that phone's own interface. Nothing could
 * answer there, and nothing said why.
 */
class LocalAddressTest {

    @Test
    fun `the Wi-Fi address wins over Wi-Fi Direct`() {
        val onARealPhone = listOf(
            "wlan0" to "172.26.252.130",
            "p2p0" to "192.168.49.1",
        )
        assertEquals("172.26.252.130", LocalAddress.choose(onARealPhone))
    }

    @Test
    fun `order of enumeration does not decide it`() {
        val other = listOf(
            "p2p0" to "192.168.49.1",
            "wlan0" to "172.26.252.130",
        )
        assertEquals("172.26.252.130", LocalAddress.choose(other))
    }

    @Test
    fun `the group owner range is refused whatever the interface is called`() {
        // Vendors name it p2p0, p2p-wlan0-0, p2p-dev-wlan0. The address is the
        // part that does not vary.
        val renamed = listOf("p2p-wlan0-0" to "192.168.49.1")
        assertNull(LocalAddress.choose(renamed))
    }

    @Test
    fun `the tethering hotspot is not the home network`() {
        // A phone sharing its own connection is 192.168.43.1 to its clients and
        // reachable at it -- but the phone by the cot is on the house Wi-Fi, and
        // that is the address it has to be given.
        val tethering = listOf(
            "ap0" to "192.168.43.1",
            "wlan0" to "10.0.0.14",
        )
        assertEquals("10.0.0.14", LocalAddress.choose(tethering))
    }

    @Test
    fun `a VPN tunnel is not offered`() {
        assertNull(LocalAddress.choose(listOf("tun0" to "10.8.0.6")))
    }

    @Test
    fun `wired beats an interface nobody can name`() {
        val mixed = listOf("rmnet0" to "10.55.1.9", "eth0" to "192.168.1.5")
        assertEquals("192.168.1.5", LocalAddress.choose(mixed))
    }

    @Test
    fun `a public address is not a home network`() {
        assertNull(LocalAddress.choose(listOf("wlan0" to "203.0.113.9")))
    }

    @Test
    fun `172 is private only in the middle`() {
        assertEquals("172.16.0.2", LocalAddress.choose(listOf("wlan0" to "172.16.0.2")))
        assertEquals("172.31.255.9", LocalAddress.choose(listOf("wlan0" to "172.31.255.9")))
        assertNull(LocalAddress.choose(listOf("wlan0" to "172.15.0.2")))
        assertNull(LocalAddress.choose(listOf("wlan0" to "172.32.0.2")))
    }

    @Test
    fun `nothing on the network means nothing to offer`() {
        assertNull(LocalAddress.choose(emptyList()))
    }

    @Test
    fun `a malformed address is not returned`() {
        assertNull(LocalAddress.choose(listOf("wlan0" to "10.0.0")))
        assertNull(LocalAddress.choose(listOf("wlan0" to "10.0.0.999")))
    }
}
