package com.nila.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Which of this phone's addresses the other phone can actually reach.
 *
 * A phone has more than one private IPv4 address and only one of them is the
 * home network. An iQOO sitting on Wi-Fi also holds `p2p0` at 192.168.49.1 --
 * the Wi-Fi Direct group-owner address, which is up on most Qualcomm builds
 * whether or not anything is using it. Tethering adds `ap0` or `swlan0`, a VPN
 * adds `tun0`, USB adds `rndis0`. All of them are site-local, so the first
 * site-local address is a coin flip, and on this hardware the coin came up
 * p2p0: both this app's screens told the user to connect to 192.168.49.1, an
 * address that on the *receiving* phone means that phone's own Wi-Fi Direct
 * interface. Nothing could ever answer there.
 *
 * So the question is asked of the system rather than guessed: the active
 * network's link properties are the addresses on the network this phone is
 * actually using. [fromInterfaces] is the fallback for when there is no active
 * network yet, and it is the enumeration done properly -- scored, with the
 * interfaces that are never the home network thrown out.
 */
object LocalAddress {

    private const val TAG = "LocalAddress"

    /**
     * The Wi-Fi Direct group owner, on every Android build since the API
     * existed. Excluded by address as well as by interface name because the
     * name varies by vendor -- `p2p0`, `p2p-wlan0-0`, `p2p-dev-wlan0` -- and
     * the address does not.
     */
    private const val WIFI_DIRECT_PREFIX = "192.168.49."

    /**
     * Interfaces that can hold a private address that no phone on the home
     * network can reach: Wi-Fi Direct, the tethering access point under its
     * several vendor names, USB tethering, and VPN tunnels.
     */
    private val NEVER_THE_HOME_NETWORK = listOf(
        "p2p", "ap0", "swlan", "softap", "rndis", "usb", "tun", "ppp", "dummy",
    )

    /** The address to hand the other phone, or null if this one is not on Wi-Fi. */
    fun of(context: Context): String? = fromActiveNetwork(context) ?: fromInterfaces()

    /**
     * Ask the system which network this phone is on, and take its address.
     *
     * Restricted to Wi-Fi and Ethernet: the link between two phones runs over
     * the home network, and offering a mobile-data address would be offering
     * one the other phone cannot route to even when it looks private behind a
     * carrier NAT.
     */
    private fun fromActiveNetwork(context: Context): String? = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        val onALocalNetwork =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onALocalNetwork) return null

        manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress && !isWifiDirect(it.hostAddress) }
            ?.hostAddress
    }.onFailure { Log.w(TAG, "no active network address", it) }.getOrNull()

    /** Every candidate the interface list offers, best first. */
    private fun fromInterfaces(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress?.let { address -> nic.name to address } }
            }
        choose(candidates)
    }.onFailure { Log.w(TAG, "could not enumerate interfaces", it) }.getOrNull()

    /**
     * Pick the address most likely to be reachable from the other phone.
     *
     * Pure, and separated from the enumeration so it can be tested against the
     * interface list a real phone produces -- which is the part that was wrong
     * and the part no emulator reproduces.
     */
    fun choose(candidates: List<Pair<String, String>>): String? = candidates
        .filter { (name, address) -> isUsable(name, address) }
        .minByOrNull { (name, _) -> rank(name) }
        ?.second

    private fun isUsable(name: String, address: String): Boolean {
        if (isWifiDirect(address)) return false
        if (NEVER_THE_HOME_NETWORK.any { name.startsWith(it, ignoreCase = true) }) return false
        return isSiteLocal(address)
    }

    /** Lower sorts first. Wi-Fi, then wired, then whatever is left. */
    private fun rank(name: String): Int = when {
        name.startsWith("wlan", ignoreCase = true) -> 0
        name.startsWith("eth", ignoreCase = true) -> 1
        else -> 2
    }

    private fun isWifiDirect(address: String?) =
        address?.startsWith(WIFI_DIRECT_PREFIX) == true

    /**
     * 10/8, 172.16/12 and 192.168/16, the same three ranges
     * `InetAddress.isSiteLocalAddress` knows, decided on the string so the
     * ranking can be tested without a device.
     */
    private fun isSiteLocal(address: String): Boolean {
        val parts = address.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            else -> false
        }
    }
}
