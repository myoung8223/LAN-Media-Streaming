package com.lanmedia.receiver

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * TLS server material using ONLY standard Java crypto (no AndroidKeyStore, no
 * external libraries).
 *
 * We generate a normal in-memory RSA key pair and a self-signed certificate
 * (built with a tiny DER encoder below, since the platform has no public API to
 * create one), then persist both to the app's private storage. Because the key
 * is an ordinary software key, Conscrypt/BoringSSL signs the TLS handshake with
 * it directly — this avoids the AndroidKeyStore-delegated signing path that
 * fails with "RSA routines: internal error" on some devices.
 *
 * The key + cert are handed to the TLS engine through a standard in-memory
 * PKCS12 keystore and the platform's default KeyManagerFactory — the canonical
 * server setup — rather than a custom KeyManager, which some Conscrypt builds
 * drive down a different (buggier) code path.
 */
object TlsUtil {
    private const val KEY_FILE = "tls_key.pk8"
    private const val CERT_FILE = "tls_cert.der"
    private const val ALIAS = "lanmedia"

    private var cachedKey: PrivateKey? = null
    private var cachedCert: X509Certificate? = null

    @Synchronized
    private fun ensure(ctx: Context) {
        if (cachedKey != null && cachedCert != null) return
        val kf = File(ctx.filesDir, KEY_FILE)
        val cf = File(ctx.filesDir, CERT_FILE)

        if (kf.exists() && cf.exists()) {
            try {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(cf.inputStream()) as X509Certificate
                cachedKey = key; cachedCert = cert
                return
            } catch (_: Exception) { /* fall through and regenerate */ }
        }

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certDer = buildSelfSigned(kp.private, kp.public.encoded, "LAN Media Receiver")
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        try { kf.writeBytes(kp.private.encoded) } catch (_: Exception) {}
        try { cf.writeBytes(certDer) } catch (_: Exception) {}
        cachedKey = kp.private; cachedCert = cert
    }

    fun serverSocketFactory(ctx: Context): SSLServerSocketFactory {
        ensure(ctx)
        // Standard server setup: put the key + cert into an in-memory PKCS12
        // keystore and let the default KeyManagerFactory feed the TLS engine.
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, cachedKey!!, CharArray(0), arrayOf(cachedCert!!))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val c = SSLContext.getInstance("TLS")
        c.init(kmf.keyManagers, null, SecureRandom())
        return c.serverSocketFactory
    }

    fun fingerprintSha256(ctx: Context): String {
        ensure(ctx)
        val d = MessageDigest.getInstance("SHA-256").digest(cachedCert!!.encoded)
        return d.joinToString(":") { "%02X".format(it) }
    }

    // ---------------- minimal DER / self-signed X.509 builder ----------------

    private fun buildSelfSigned(privateKey: PrivateKey, spki: ByteArray, cn: String): ByteArray {
        val alg = seq(oid("1.2.840.113549.1.1.11"), nullDer())     // sha256WithRSAEncryption
        val name = nameCn(cn)
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz)
        cal.clear(); cal.set(2024, 0, 1, 0, 0, 0); val notBefore = cal.time
        cal.clear(); cal.set(2049, 11, 31, 23, 59, 59); val notAfter = cal.time

        val serial = BigInteger(64, SecureRandom()).let { if (it.signum() == 0) BigInteger.ONE else it }

        val tbs = seq(
            explicit0(intDer(BigInteger.valueOf(2))),  // version v3
            intDer(serial),
            alg,
            name,
            seq(utcTime(notBefore), utcTime(notAfter)),
            name,
            spki
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(tbs); sign()
        }
        return seq(tbs, alg, bitString(sig))
    }

    private fun len(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        var x = n
        val tmp = ArrayList<Byte>()
        while (x > 0) { tmp.add(0, (x and 0xff).toByte()); x = x ushr 8 }
        val out = ByteArray(1 + tmp.size)
        out[0] = (0x80 or tmp.size).toByte()
        for (i in tmp.indices) out[i + 1] = tmp[i]
        return out
    }
    private fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + len(content.size) + content
    private fun seq(vararg items: ByteArray): ByteArray {
        var body = ByteArray(0); for (i in items) body += i; return tlv(0x30, body)
    }
    private fun set(vararg items: ByteArray): ByteArray {
        var body = ByteArray(0); for (i in items) body += i; return tlv(0x31, body)
    }
    private fun intDer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())
    private fun nullDer(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun utf8(s: String): ByteArray = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    private fun bitString(b: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0x00) + b)
    private fun explicit0(content: ByteArray): ByteArray = tlv(0xA0, content)
    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }
    private fun oid(s: String): ByteArray {
        val parts = s.split(".").map { it.toInt() }
        var body = byteArrayOf((40 * parts[0] + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var p = parts[i]
            val stack = ArrayList<Int>()
            stack.add(p and 0x7f); p = p ushr 7
            while (p > 0) { stack.add((p and 0x7f) or 0x80); p = p ushr 7 }
            for (j in stack.indices.reversed()) body += stack[j].toByte()
        }
        return tlv(0x06, body)
    }
    private fun nameCn(cn: String): ByteArray = seq(set(seq(oid("2.5.4.3"), utf8(cn))))
}
