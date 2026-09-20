package com.lanmedia.sender

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredReceiver(val ip: String, val port: Int, val tls: Boolean)

/**
 * UDP name discovery (client side): broadcast a query and wait for the matching
 * panel to answer with its current IP/port. Mirrors the Windows sender's
 * Discovery.Resolve and the receivers' discovery responder.
 */
object Discovery {
    fun resolve(name: String, timeoutMs: Int = 1500): DiscoveredReceiver? {
        if (name.isBlank()) return null
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }
            val query = JSONObject()
                .put("magic", Protocol.DISCOVERY_MAGIC)
                .put("q", name)
                .toString() + "\n"
            val qb = query.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(qb, qb.size,
                InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (e: Exception) { break }
                val msg = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8).trim()
                val json = try { JSONObject(msg) } catch (e: Exception) { continue }
                if (json.optString("magic") != Protocol.DISCOVERY_MAGIC) continue
                if (!json.optString("name", "").equals(name, ignoreCase = true)) continue
                val ip = pkt.address?.hostAddress ?: continue
                return DiscoveredReceiver(ip,
                    json.optInt("port", Protocol.DEFAULT_PORT),
                    json.optBoolean("tls", true))
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }
}
