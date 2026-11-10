package com.cmts.mobile.remote

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks a LAN IPv4 for pairing paste strings.
 * Prefers wlan*, then non-rmnet site-local, then any site-local, then any up IPv4.
 */
object LocalIpv4 {
    data class Candidate(
        val iface: String,
        val host: String,
        val siteLocal: Boolean,
    )

    /** Enumerate up interfaces; null when nothing usable. */
    fun resolvePreferred(): String? = pickPreferred(enumerateCandidates())

    fun buildPairingUri(
        ipv4: String,
        token: String,
        port: Int = RemoteIdentityStore.DEFAULT_PORT,
        path: String = RemoteIdentityStore.WS_PATH,
    ): String = "cmts://$ipv4:$port$path?token=$token"

    fun pickPreferred(candidates: List<Candidate>): String? {
        val candidates = candidates.filter { !it.iface.startsWith("rmnet", true) && !it.iface.startsWith("ccmni", true) && !it.iface.startsWith("tun", true) }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.iface.startsWith("wlan", ignoreCase = true) }?.host?.let { return it }
        val nonRmnet = candidates.filter { !it.iface.startsWith("rmnet", ignoreCase = true) }
        nonRmnet.firstOrNull { it.siteLocal }?.host?.let { return it }
        nonRmnet.firstOrNull()?.host?.let { return it }
        candidates.firstOrNull { it.siteLocal }?.host?.let { return it }
        return candidates.firstOrNull()?.host
    }

    fun enumerateCandidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (iface in ifaces) {
            if (!runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)) continue
            val name = iface.name ?: continue
            for (addr in iface.inetAddresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.startsWith("127.")) continue
                out += Candidate(
                    iface = name,
                    host = host,
                    siteLocal = addr.isSiteLocalAddress,
                )
            }
        }
        return out
    }
}
