package com.lanmedia.sender

import java.io.InputStream
import java.security.MessageDigest

/**
 * Wire-protocol constants and helpers, matching the receivers (Protocol.kt on
 * Android, Protocol.cs on Windows). v3 muxed stream:
 *   handshake: one line of JSON, then the receiver replies "{\"ok\":true}\n"
 *   media:     repeating [type:1][ptsMs:8 BE][len:4 BE][payload]
 *              type 0 = Opus audio, type 1 = H.264 (Annex-B) video
 */
object Protocol {
    const val MAGIC = "LANMED01"
    const val DISCOVERY_MAGIC = "LANDISC1"
    const val DEFAULT_PORT = 45788
    const val DISCOVERY_PORT = 45789
    const val VIDEO_VERSION = 3
    const val STREAM_AUDIO = 0
    const val STREAM_VIDEO = 1

    private const val HEX = "0123456789abcdef"

    /** Lowercase hex SHA-256. Empty input → "" (so a blank password means "open"). */
    fun sha256(s: String): String {
        if (s.isEmpty()) return ""
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0xf])
        }
        return sb.toString()
    }

    /** Read one '\n'-terminated line from a stream (used for the handshake reply). */
    fun readLine(input: InputStream, maxLen: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > maxLen) return sb.toString()
        }
    }
}
