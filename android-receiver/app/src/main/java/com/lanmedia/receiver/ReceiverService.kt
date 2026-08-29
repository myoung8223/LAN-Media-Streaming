package com.lanmedia.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Foreground service that listens on a TCP port and plays the incoming PCM
 * stream. Runs as a media-playback foreground service so Android keeps audio
 * alive when the app is not in front.
 */
class ReceiverService : Service() {

    private var listenThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var port = Protocol.DEFAULT_PORT
    private var password = ""
    private var useTls = true
    private var panelName = ""
    private var discoveryThread: Thread? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var lastError: String? = null

    private fun waitingStatus(): String {
        val named = if (panelName.isNotEmpty()) " “$panelName”" else ""
        val base = "Waiting for a sender…$named  (port $port)" + if (useTls) " 🔒" else ""
        val err = lastError
        return if (err != null) "$base\n⚠ $err" else base
    }

    /**
     * Record an error both in memory (shown on the notification/UI until the
     * next successful connection) and appended to a plain-text file that can be
     * pulled off the panel with a file manager or MTP, so a fast-flickering
     * on-screen message is never the only copy.
     * Path: Android/data/com.lanmedia.receiver/files/tls_error.log
     */
    private fun recordError(msg: String) {
        lastError = msg
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                File(dir, "tls_error.log").appendText("[$stamp] $msg\n")
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        port = intent?.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT) ?: Protocol.DEFAULT_PORT
        password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        useTls = intent?.getBooleanExtra(EXTRA_TLS, true) ?: true
        panelName = intent?.getStringExtra(EXTRA_NAME) ?: panelName

        startForegroundInternal("Starting…")
        acquireLocks()

        if (!running) {
            running = true
            isRunning = true
            lastError = null
            // Start each run with a fresh error log.
            try { getExternalFilesDir(null)?.let { File(it, "tls_error.log").writeText("") } } catch (_: Exception) {}
            listenThread = Thread({ listenLoop() }, "lan-audio-listen").also { it.start() }
            discoveryThread = Thread({ discoveryLoop() }, "lan-audio-discovery").also { it.start() }
        }
        // Redeliver the last intent on restart so port/password survive.
        return START_REDELIVER_INTENT
    }

    // ---------- networking ----------

    private fun listenLoop() {
        try {
            val ss = if (useTls) {
                (TlsUtil.serverSocketFactory(this).createServerSocket() as javax.net.ssl.SSLServerSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.2")
                }
            } else {
                ServerSocket()
            }
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            updateStatus(waitingStatus())

            while (running) {
                val sock = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running) updateStatus("Listener error: ${e.message}")
                    break
                }
                clientSocket = sock
                handleClient(sock)
                clientSocket = null
                if (running) updateStatus(waitingStatus())
            }
        } catch (e: Exception) {
            updateStatus("Could not open port $port: ${e.message}")
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(sock: Socket) {
        var track: AudioTrack? = null
        var streamed = false
        try {
            sock.tcpNoDelay = true

            if (sock is javax.net.ssl.SSLSocket) {
                try {
                    sock.startHandshake()
                    lastError = null
                } catch (e: Exception) {
                    android.util.Log.e("LanMedia", "TLS handshake failed", e)
                    val cause = e.cause?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                    recordError("TLS: ${e.javaClass.simpleName}: ${e.message}$cause")
                    updateStatus(waitingStatus())
                    return
                }
            }

            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val line = Protocol.readLine(input)
            if (line == null) { return }

            val hs = try { JSONObject(line) } catch (e: Exception) {
                reject(output, "bad handshake"); return
            }
            if (hs.optString("magic") != Protocol.MAGIC) { reject(output, "bad magic"); return }

            val expected = Protocol.sha256(password)
            val auth = hs.optString("auth", "")
            if (expected.isNotEmpty() && auth != expected) {
                reject(output, "bad password")
                updateStatus("Blocked a sender (wrong/no password)")
                return
            }

            // v3 video mode: show full-screen video instead of playing audio.
            if (hs.optBoolean("video", false)) {
                output.write("{\"ok\":true}\n".toByteArray(Charsets.UTF_8))
                output.flush()
                streamed = true
                lastError = null
                handleVideoStream(sock, input, hs)
                return
            }

            val codec = hs.optString("codec", "pcm")
            val useOpus = codec.equals("opus", ignoreCase = true)

            // Opus is fixed at 48 kHz stereo; PCM uses the negotiated format.
            val sampleRate = if (useOpus) 48000 else hs.optInt("sampleRate", 48000).coerceIn(8000, 192000)
            val channels = if (useOpus) 2 else hs.optInt("channels", 2).coerceIn(1, 2)

            output.write("{\"ok\":true}\n".toByteArray(Charsets.UTF_8))
            output.flush()

            track = buildTrack(sampleRate, channels)
            track.play()
            streamed = true
            lastError = null
            val host = sock.inetAddress.hostAddress
            updateStatus("Receiving audio · ${if (useOpus) "Opus" else "PCM"}  ($host)")

            if (useOpus) {
                val decoder = OpusStreamDecoder(sampleRate, channels)
                try {
                    val lenBuf = ByteArray(2)
                    while (running && !sock.isClosed) {
                        if (!readFully(input, lenBuf, 2)) break
                        val n = ((lenBuf[0].toInt() and 0xff) shl 8) or (lenBuf[1].toInt() and 0xff)
                        if (n <= 0 || n > 8192) break // sanity bound on a single Opus packet
                        val pkt = ByteArray(n)
                        if (!readFully(input, pkt, n)) break
                        val pcm = decoder.decode(pkt)
                        if (pcm.isNotEmpty()) track.write(pcm, 0, pcm.size)
                    }
                } finally {
                    decoder.release()
                }
            } else {
                val buf = ByteArray(8192)
                while (running && !sock.isClosed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) track.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            // Surface any failure (handshake succeeded but something later broke)
            // so we can see exactly where and why it dropped.
            android.util.Log.e("LanMedia", "connection error (streamed=$streamed)", e)
            val cause = e.cause?.let { " / ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            val phase = if (streamed) "while streaming" else "after handshake"
            recordError("$phase: ${e.javaClass.simpleName}: ${e.message}$cause")
            if (running) updateStatus(waitingStatus())
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /**
     * Answers UDP name-discovery queries so the sender can find this panel by
     * name (and its current IP/port) without a hard-coded address. Best-effort:
     * any failure here never affects audio.
     */
    private fun discoveryLoop() {
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
            discoverySocket = sock
            val buf = ByteArray(2048)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: Exception) {
                    if (running) continue else break
                }
                val msg = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8).trim()
                val json = try { JSONObject(msg) } catch (e: Exception) { continue }
                if (json.optString("magic") != Protocol.DISCOVERY_MAGIC) continue

                // Reply only if the query targets this panel (or is a wildcard).
                val q = json.optString("q", "")
                if (q.isNotEmpty() && !q.equals(panelName, ignoreCase = true)) continue

                val reply = "{\"magic\":\"${Protocol.DISCOVERY_MAGIC}\"," +
                        "\"name\":\"${jsonEscape(panelName)}\"," +
                        "\"port\":$port,\"tls\":$useTls}\n"
                val rb = reply.toByteArray(Charsets.UTF_8)
                try { sock.send(DatagramPacket(rb, rb.size, pkt.address, pkt.port)) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("LanMedia", "discovery responder error", e)
        } finally {
            try { discoverySocket?.close() } catch (_: Exception) {}
            discoverySocket = null
        }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /**
     * v3 video: read muxed frames ([type:1][pts:8][len:4][payload]) and hand the
     * H.264 access units to the full-screen VideoActivity for decode + display.
     */
    private fun handleVideoStream(sock: Socket, input: java.io.InputStream, hs: JSONObject) {
        val w = hs.optInt("width", 1920)
        val h = hs.optInt("height", 1080)
        val hasAudio = hs.optBoolean("audio", false)
        VideoStream.begin(w, h, hasAudio)
        launchVideoActivity()
        updateStatus("Receiving video${if (hasAudio) " + audio" else ""}  (${sock.inetAddress.hostAddress})")

        var track: AudioTrack? = null
        var opus: OpusStreamDecoder? = null
        if (hasAudio) {
            track = buildTrack(48000, 2)
            track.play()
            primeSilence(track, VideoStream.DELAY_MS.toInt())  // delay audio to match video
            opus = OpusStreamDecoder(48000, 2)
        }
        var lastAudioNanos = 0L

        val header = ByteArray(13)
        try {
            while (running && !sock.isClosed) {
                if (!readFully(input, header, 13)) break
                val type = header[0].toInt() and 0xff
                var pts = 0L
                for (k in 1..8) pts = (pts shl 8) or (header[k].toLong() and 0xff)
                val len = ((header[9].toInt() and 0xff) shl 24) or
                        ((header[10].toInt() and 0xff) shl 16) or
                        ((header[11].toInt() and 0xff) shl 8) or
                        (header[12].toInt() and 0xff)
                if (len <= 0 || len > 20_000_000) break
                val payload = ByteArray(len)
                if (!readFully(input, payload, len)) break

                VideoStream.noteBase(pts) // anchor the timeline to the first packet

                if (type == Protocol.STREAM_VIDEO) {
                    VideoStream.pushVideo(pts, payload)
                } else if (type == Protocol.STREAM_AUDIO && opus != null && track != null) {
                    val now = System.nanoTime()
                    // After a silence gap, re-prime so audio stays ~DELAY behind video.
                    if (lastAudioNanos != 0L && now - lastAudioNanos > 250_000_000L)
                        primeSilence(track, VideoStream.DELAY_MS.toInt())
                    lastAudioNanos = now
                    val pcm = opus.decode(payload)
                    if (pcm.isNotEmpty()) track.write(pcm, 0, pcm.size)
                }
            }
        } finally {
            VideoStream.end()
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { opus?.release() } catch (_: Exception) {}
        }
    }

    /** Write [ms] of silence so subsequent audio plays that far behind live. */
    private fun primeSilence(track: AudioTrack, ms: Int) {
        val bytes = 48000 * 2 * 2 * ms / 1000  // 48kHz stereo S16
        val silence = ByteArray(bytes)
        var off = 0
        while (off < bytes) {
            val n = track.write(silence, off, bytes - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun launchVideoActivity() {
        try {
            val i = Intent(this, VideoActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.e("LanMedia", "could not launch VideoActivity", e)
        }
    }

    /** Read exactly [len] bytes into [buf]; false if the stream ends first. */
    private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun reject(output: java.io.OutputStream, reason: String) {
        try {
            output.write("{\"ok\":false,\"error\":\"$reason\"}\n".toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun buildTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        // ~250 ms of buffering to smooth network jitter
        val target = sampleRate * channels * 2 / 4
        val bufSize = maxOf(minBuf, target)

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioTrack(
            attrs, format, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    // ---------- locks ----------

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanmedia:cpu").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {}
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "lanmedia:wifi").apply {
                setReferenceCounted(false); acquire()
            }
            // Needed so Wi-Fi delivers broadcast/multicast discovery packets.
            multicastLock = wm.createMulticastLock("lanmedia:mcast").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null; wifiLock = null; multicastLock = null
    }

    // ---------- foreground notification ----------

    private fun startForegroundInternal(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Remove the old badge-enabled channel from earlier builds. Channel
            // settings are immutable once created, so a new id is the only reliable
            // way to make setShowBadge(false) take effect for existing installs.
            nm.deleteNotificationChannel("receiver")
            val ch = NotificationChannel(
                CHANNEL_ID, "Media receiver", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the receiver listening"
                setShowBadge(false)   // no launcher badge for the ongoing notification
            }
            nm.createNotificationChannel(ch)
        }
        val notif = buildNotification(text)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Media Receiver")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setNumber(0)                                   // no count on the badge
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setSilent(true)
            .build()
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(text)) } catch (_: Exception) {}
        mainHandler.post { statusListener?.invoke(text) }
    }

    private fun stopEverything() {
        running = false
        isRunning = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        releaseLocks()
        lastStatus = "Stopped"
        mainHandler.post { statusListener?.invoke("Stopped") }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "receiver2"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.lanmedia.receiver.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_TLS = "tls"
        const val EXTRA_NAME = "name"

        private val mainHandler = Handler(Looper.getMainLooper())

        /** Latest status text, readable by the UI when it (re)binds visually. */
        @Volatile var lastStatus: String = "Stopped"
        /** Set by MainActivity while visible to receive live status updates. */
        @Volatile var statusListener: ((String) -> Unit)? = null
        @Volatile var isRunning: Boolean = false
    }
}
