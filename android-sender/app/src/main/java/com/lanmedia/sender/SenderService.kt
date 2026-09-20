package com.lanmedia.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service that owns the MediaProjection, encodes screen (H.264) and
 * optional system audio (Opus), and muxes them to a LAN Media receiver using the
 * v3 protocol. Mirrors the Windows sender's VideoStreamer, inverted onto Android.
 */
class SenderService : Service() {

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var video: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var audioActive = false

    private var socket: Socket? = null
    @Volatile private var outStream: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var baseNanos = 0L
    private val header = ByteArray(13)

    private var netThread: Thread? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // connection params (from the launch intent)
    private var name = ""
    private var host = ""
    private var port = Protocol.DEFAULT_PORT
    private var password = ""
    private var useTls = true
    private var fps = 30

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        name = intent?.getStringExtra(EXTRA_NAME)?.trim() ?: ""
        host = intent?.getStringExtra(EXTRA_HOST)?.trim() ?: ""
        port = intent?.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT) ?: Protocol.DEFAULT_PORT
        password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        useTls = intent?.getBooleanExtra(EXTRA_TLS, true) ?: true
        fps = (intent?.getIntExtra(EXTRA_FPS, 30) ?: 30).coerceIn(10, 60)
        val reqW = (intent?.getIntExtra(EXTRA_WIDTH, 1920) ?: 1920).coerceIn(320, 3840)
        val reqH = (intent?.getIntExtra(EXTRA_HEIGHT, 1080) ?: 1080).coerceIn(240, 2160)
        val bitrate = (intent?.getIntExtra(EXTRA_BITRATE, 10) ?: 10).coerceIn(1, 50) * 1_000_000
        val wantAudio = intent?.getBooleanExtra(EXTRA_AUDIO, true) ?: true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)

        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val micType = wantAudio && hasRecordAudio && Build.VERSION.SDK_INT >= 30

        startForegroundInternal("Starting…", micType)

        if (data == null) { updateStatus("No screen-capture permission"); stopEverything(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) { updateStatus("Screen capture was denied"); stopEverything(); return START_NOT_STICKY }
        projection = proj
        // Required on Android 14+: a callback must be registered before capture.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, mainHandler)

        // Fit the device screen inside the requested W×H box, aspect preserved, never upscaled.
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val scrW = dm.widthPixels; val scrH = dm.heightPixels
        val scale = min(1.0, min(reqW.toDouble() / scrW, reqH.toDouble() / scrH))
        val outW = ((scrW * scale).roundToInt()) and 1.inv()
        val outH = ((scrH * scale).roundToInt()) and 1.inv()

        try {
            val v = VideoEncoder(outW, outH, fps, bitrate)
            v.start()
            video = v
            virtualDisplay = proj.createVirtualDisplay(
                "lanmedia", outW, outH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                v.inputSurface, null, null)
        } catch (e: Exception) {
            updateStatus("Encoder init failed: ${e.message}"); stopEverything(); return START_NOT_STICKY
        }

        audioActive = false
        if (wantAudio && hasRecordAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val a = AudioEncoder(proj, 128_000)
                a.start()
                audio = a; audioActive = true
            } catch (e: Exception) {
                audio = null; audioActive = false
                updateStatus("Audio unavailable — video only (${e.message})")
            }
        }

        running = true
        isRunning = true
        val fOutW = outW; val fOutH = outH
        netThread = Thread({ networkLoop(fOutW, fOutH) }, "lms-net").also { it.start() }
        return START_NOT_STICKY
    }

    private fun networkLoop(outW: Int, outH: Int) {
        try {
            var targetIp = host
            var targetPort = port
            if (name.isNotBlank()) {
                updateStatus("Looking for “$name”…")
                val found = Discovery.resolve(name, 1500)
                if (found != null) { targetIp = found.ip; targetPort = found.port }
                else if (host.isBlank()) { updateStatus("“$name” not found"); stopEverything(); return }
                else updateStatus("“$name” not found — trying $host")
            }
            if (targetIp.isBlank()) { updateStatus("Enter a receiver name, IP, or hostname"); stopEverything(); return }

            updateStatus("Connecting to $targetIp:$targetPort …")
            val sock: Socket = if (useTls) {
                TlsUtil.connect(targetIp, targetPort, 5000, getPrefs().getString("pinnedFp", "") ?: "") { fp -> savePin(fp) }
            } else {
                Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(targetIp, targetPort), 5000) }
            }
            socket = sock
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val audioJson = if (audioActive)
                ",\"audio\":true,\"acodec\":\"opus\",\"audioSampleRate\":48000,\"audioChannels\":2"
            else ",\"audio\":false"
            val hello = "{\"magic\":\"${Protocol.MAGIC}\",\"version\":${Protocol.VIDEO_VERSION}," +
                "\"auth\":\"${Protocol.sha256(password)}\",\"video\":true,\"vcodec\":\"h264\"," +
                "\"width\":$outW,\"height\":$outH,\"fps\":$fps$audioJson}\n"
            out.write(hello.toByteArray(Charsets.UTF_8)); out.flush()

            val resp = Protocol.readLine(inp) ?: throw IOException("no handshake reply")
            val ok = try { JSONObject(resp).optBoolean("ok", false) } catch (e: Exception) { false }
            if (!ok) {
                val err = try { JSONObject(resp).optString("error", "rejected") } catch (e: Exception) { "rejected" }
                updateStatus("Rejected by receiver: $err"); stopEverything(); return
            }

            val who = if (name.isNotBlank()) "“$name” ($targetIp)" else "$targetIp:$targetPort"
            updateStatus("● Streaming to $who" + (if (audioActive) " + audio" else "") + (if (useTls) " 🔒" else ""))

            outStream = out
            baseNanos = System.nanoTime()

            videoThread = Thread({ video?.drainInto({ running }) { writePacket(Protocol.STREAM_VIDEO, it) } }, "lms-vid")
                .also { it.start() }
            if (audioActive) {
                audioThread = Thread({ audio?.loop({ running }) { writePacket(Protocol.STREAM_AUDIO, it) } }, "lms-aud")
                    .also { it.start() }
            }

            videoThread?.join()   // returns when running goes false or the encoder ends
        } catch (e: Exception) {
            if (running) updateStatus("Disconnected: ${e.message}")
        } finally {
            stopEverything()
        }
    }

    /** Mux one packet: [type:1][ptsMs:8 BE][len:4 BE][payload]. Thread-safe. */
    private fun writePacket(type: Int, payload: ByteArray) {
        val out = outStream ?: return
        val pts = (System.nanoTime() - baseNanos) / 1_000_000L
        try {
            synchronized(writeLock) {
                header[0] = type.toByte()
                for (k in 0 until 8) header[1 + k] = (pts shr (8 * (7 - k))).toByte()
                val len = payload.size
                header[9] = (len ushr 24).toByte()
                header[10] = (len ushr 16).toByte()
                header[11] = (len ushr 8).toByte()
                header[12] = len.toByte()
                out.write(header, 0, 13)
                out.write(payload)
            }
        } catch (e: Exception) {
            running = false   // disconnect; the loops will unwind
        }
    }

    private fun savePin(fp: String) {
        getPrefs().edit().putString("pinnedFp", fp).apply()
        mainHandler.post { onPinnedChanged?.invoke() }
    }

    private fun getPrefs() = getSharedPreferences("lanmediasender", Context.MODE_PRIVATE)

    // ---------- foreground notification ----------

    private fun startForegroundInternal(text: String, withMic: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen streaming", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Active while mirroring this device"; setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        // Foreground-service types exist only on API 29+ (microphone type on 30+).
        // On Android 9 (API 28) we start a plain foreground service (type 0).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            t
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Media Sender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
        mainHandler.post { statusListener?.invoke(text) }
    }

    private fun stopEverything() {
        if (!running && !isRunning && projection == null) return
        running = false
        isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { video?.stop() } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; video = null; audio = null; outStream = null; socket = null; projection = null
        lastStatus = "Stopped"
        mainHandler.post { statusListener?.invoke("Stopped") }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "sender"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.lanmedia.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_TLS = "tls"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AUDIO = "audio"

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile var isRunning: Boolean = false
        @Volatile var lastStatus: String = "Idle"
        @Volatile var statusListener: ((String) -> Unit)? = null
        @Volatile var onPinnedChanged: (() -> Unit)? = null
    }
}
