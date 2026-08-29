package com.lanmedia.receiver

import java.io.InputStream
import java.security.MessageDigest

/**
 * LAN Media wire protocol (v1) — deliberately tiny and auditable.
 *
 * Transport: a single TCP connection, sender -> receiver.
 *
 * 1. Sender connects and sends ONE line of UTF-8 JSON terminated by '\n':
 *      {"magic":"LANMED01","version":1,"auth":"<sha256hex|empty>",
 *       "sampleRate":48000,"channels":2,"bits":16}
 *    - "auth" is the lowercase hex SHA-256 of the shared password, or "" if none.
 *
 * 2. Receiver replies with ONE line of UTF-8 JSON terminated by '\n':
 *      {"ok":true}                      (accepted; streaming begins)
 *      {"ok":false,"error":"reason"}    (rejected; connection closed)
 *
 * 3. After an accepted handshake, the sender streams raw interleaved
 *    little-endian PCM (signed 16-bit by default) continuously until the
 *    socket closes. There is no per-frame framing — the format agreed in the
 *    handshake is constant for the life of the connection.
 *
 * No third-party services, no encryption in v1 (LAN-trusted; TLS is planned).
 */
object Protocol {
    const val MAGIC = "LANMED01"
    const val VERSION = 2   // v2 adds optional Opus codec negotiation
    const val DEFAULT_PORT = 45788

    /** UDP name-discovery (fixed, independent of the audio port). */
    const val DISCOVERY_PORT = 45789
    const val DISCOVERY_MAGIC = "LANDISC1"

    // v3 muxed framing: [type:1][ptsMs:8 BE][len:4 BE][payload].
    const val STREAM_AUDIO = 0
    const val STREAM_VIDEO = 1

    /** Lowercase hex SHA-256 of [s]. Empty string in -> empty string out. */
    fun sha256(s: String): String {
        if (s.isEmpty()) return ""
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    /**
     * Reads a single '\n'-terminated line directly from the raw stream, byte by
     * byte, so no PCM bytes are ever swallowed into a buffered reader. Returns
     * null on EOF before any data. Caps the line length to avoid abuse.
     */
    fun readLine(input: InputStream, maxLen: Int = 8192): String? {
        val buf = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (buf.isEmpty()) null else buf.toString()
            if (c == '\n'.code) return buf.toString()
            if (c != '\r'.code) buf.append(c.toChar())
            if (buf.length > maxLen) return buf.toString()
        }
    }
}
