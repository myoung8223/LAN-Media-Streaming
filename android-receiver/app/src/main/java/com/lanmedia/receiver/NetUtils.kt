package com.lanmedia.receiver

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** Best-effort site-local IPv4 address of this device (what the sender types). */
    fun localIpv4(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "(no network)"
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                // skip typical virtual/tunnel interfaces
                if (name.startsWith("dummy") || name.startsWith("rmnet")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "(no Wi-Fi address)"
    }
}
