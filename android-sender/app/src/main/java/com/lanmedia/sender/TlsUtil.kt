package com.lanmedia.sender

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS client with trust-on-first-use certificate pinning, mirroring the Windows
 * sender: the first time we connect to a receiver we record its certificate
 * fingerprint; afterwards the certificate must match or the handshake fails.
 */
object TlsUtil {

    /** Uppercase colon-separated SHA-256 of a certificate (matches the receiver's display). */
    fun fingerprintOf(cert: X509Certificate): String {
        val d = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(d.size * 3)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xff
            sb.append(hex[v ushr 4]); sb.append(hex[v and 0xf])
            if (i != d.size - 1) sb.append(':')
        }
        return sb.toString()
    }

    private fun norm(fp: String) = fp.replace(":", "").replace(" ", "").uppercase()

    /**
     * Open a TLS connection with TOFU pinning. If [pinnedFp] is blank the server's
     * cert is accepted and reported via [onPin] (persist it). Otherwise the server
     * cert must match [pinnedFp] or a CertificateException is thrown.
     */
    fun connect(
        host: String, port: Int, connectTimeoutMs: Int,
        pinnedFp: String, onPin: (String) -> Unit
    ): SSLSocket {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
                val fp = fingerprintOf(leaf)
                if (pinnedFp.isBlank()) { onPin(fp); return }
                if (norm(fp) != norm(pinnedFp))
                    throw CertificateException("certificate fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())

        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)

        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        // Receivers accept TLS 1.2; keep the client on 1.2 for broad device support.
        ssl.enabledProtocols = arrayOf("TLSv1.2")
        ssl.startHandshake()
        return ssl
    }
}
