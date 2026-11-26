package com.shzxm.carwithingateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class NeighborEntry(
    val ip: String,
    val mac: String,
    val state: String,
)

object UpstreamDiscovery {
    private val ipv4Regex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    /**
     * Reads Android ARP/neighbor table via root `ip neigh` and probes `targetPort` (default 9888)
     * to automatically locate the main iPhone running CarWithin.
     */
    suspend fun discover(targetPort: Int, preferredMac: String = ""): String? = withContext(Dispatchers.IO) {
        val entries = readNeighbors()
        if (entries.isEmpty()) return@withContext null

        val normalizedMac = preferredMac.trim().lowercase()
        if (normalizedMac.isNotEmpty()) {
            val macMatch = entries.firstOrNull { it.mac.equals(normalizedMac, ignoreCase = true) }
            if (macMatch != null) {
                return@withContext "http://${macMatch.ip}:$targetPort"
            }
        }

        // Probe all reachable/stale LAN neighbors concurrently for open CarWithin port
        val found = coroutineScope {
            entries.map { entry ->
                async(Dispatchers.IO) {
                    if (isPortOpen(entry.ip, targetPort, 350)) entry.ip else null
                }
            }.awaitAll().firstOrNull { it != null }
        }

        found?.let { "http://$it:$targetPort" }
    }

    private fun readNeighbors(): List<NeighborEntry> {
        val res = RootIptables.runRoot("ip -4 neigh show")
        if (res.exitCode != 0 || res.stdout.isBlank()) return emptyList()

        val result = mutableListOf<NeighborEntry>()
        res.stdout.lineSequence().forEach { rawLine ->
            val parts = rawLine.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return@forEach
            val ip = parts[0]
            if (!ipv4Regex.matches(ip) || !isPrivateLanIp(ip)) return@forEach

            val state = parts.lastOrNull()?.uppercase() ?: ""
            if (state == "FAILED" || state == "INCOMPLETE") return@forEach

            val lladdrIdx = parts.indexOf("lladdr")
            val mac = if (lladdrIdx >= 0 && lladdrIdx + 1 < parts.size) parts[lladdrIdx + 1] else ""
            result.add(NeighborEntry(ip = ip, mac = mac, state = state))
        }
        return result
    }

    private fun isPrivateLanIp(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
