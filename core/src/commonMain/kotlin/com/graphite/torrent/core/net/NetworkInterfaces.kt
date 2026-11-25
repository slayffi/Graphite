package com.graphite.torrent.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** A network adapter the user can bind torrent traffic to (for example a VPN tunnel). */
data class NetInterface(
    val name: String,
    val displayName: String,
    val addresses: List<String>,
    val isUp: Boolean,
    val isLikelyVpn: Boolean,
)

object NetworkInterfaces {
    private val vpnHints = listOf("tun", "tap", "wg", "ppp", "ipsec", "vpn", "wireguard", "openvpn", "nord", "proton", "mullvad", "utun")

    fun list(): List<NetInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !it.isLoopback }
            .map { ni ->
                val addresses = ni.inetAddresses.toList()
                    .filter { it is Inet4Address || (it is Inet6Address && !it.isLinkLocalAddress) }
                    .map { addr -> addr.hostAddress.substringBefore('%') }
                val label = "${ni.name} ${ni.displayName}".lowercase()
                NetInterface(
                    name = ni.name,
                    displayName = ni.displayName ?: ni.name,
                    addresses = addresses,
                    isUp = runCatching { ni.isUp }.getOrDefault(false),
                    isLikelyVpn = vpnHints.any { it in label } || runCatching { ni.isPointToPoint }.getOrDefault(false),
                )
            }
            .sortedWith(compareByDescending<NetInterface> { it.isLikelyVpn }.thenBy { it.name })
    }.getOrDefault(emptyList())

    /** Addresses of the named interface when it is up and has an address; empty otherwise. */
    fun addressesOf(name: String): List<String> =
        list().firstOrNull { it.name == name && it.isUp }?.addresses.orEmpty()

    /** True when at least one non-loopback interface is up with an address. */
    fun hasConnectivity(): Boolean = list().any { it.isUp && it.addresses.isNotEmpty() }
}
