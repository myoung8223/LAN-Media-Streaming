package com.lanmedia.receiver

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Full-screen H.264 video display. The service pushes access units into
 * [VideoStream]; a decode thread here feeds them to a MediaCodec decoder that
 * renders straight onto this activity's Surface.
 */
class VideoActivity : Activity(), SurfaceHolder.Callback {

    @Volatile private var surface: Surface? = null
    @Volatile private var running = false
    private var decodeThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wake the panel and show over the lock screen when a stream arrives.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Clear the "incoming stream" full-screen-intent notification, if one was posted.
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(ReceiverService.INCOMING_NOTIF_ID)
        } catch (_: Exception) {}
        hideSystemBars()

        val sv = SurfaceView(this)
        setContentView(sv)
        sv.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startDecode()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        stopDecode()
    }

    override fun onDestroy() {
        stopDecode()
        super.onDestroy()
    }

    private fun startDecode() {
        if (running) return
        running = true
        decodeThread = Thread({ decodeLoop() }, "lan-video-decode").also { it.start() }
    }

    private fun stopDecode() {
        running = false
        try { decodeThread?.join(500) } catch (_: Exception) {}
        decodeThread = null
    }

    private fun decodeLoop() {
        val surf = surface ?: return
        var codec: MediaCodec? = null
        try {
            // Wait for the first keyframe packet (it carries SPS + PPS).
            var first: VideoStream.Packet? = null
            while (running && first == null) {
                val p = VideoStream.queue.poll(200, TimeUnit.MILLISECONDS)
                if (p == null) {
                    if (!VideoStream.active) return
                } else if (findNal(p.data, 7) != null) {
                    first = p
                }
                // non-keyframe packets before the first SPS are skipped
            }
            val startPkt = first ?: return

            val sps = findNal(startPkt.data, 7)
            val pps = findNal(startPkt.data, 8)
            val fmt = MediaFormat.createVideoFormat("video/avc", VideoStream.width, VideoStream.height)
            if (sps != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps != null) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

            codec = MediaCodec.createDecoderByType("video/avc")
            codec.configure(fmt, surf, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var pkt: VideoStream.Packet? = startPkt

            while (running) {
                if (pkt == null) pkt = VideoStream.queue.poll(200, TimeUnit.MILLISECONDS)
                if (pkt == null) {
                    if (!VideoStream.active) break else continue
                }

                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val cur = pkt!!
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear()
                    ib.put(cur.data)
                    codec.queueInputBuffer(inIdx, 0, cur.data.size, cur.ptsMs * 1000L, 0)
                    pkt = null
                }

                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    renderOutput(codec, outIdx, info)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LanMedia", "video decode error", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            runOnUiThread { finish() }
        }
    }

    /**
     * With audio, schedule each frame for display at its slot on the shared
     * timeline (so it lines up with the audio). Without audio, render immediately.
     */
    private fun renderOutput(codec: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        if (VideoStream.hasAudio && VideoStream.baseNanos != 0L) {
            val framePtsMs = info.presentationTimeUs / 1000
            val target = VideoStream.baseNanos +
                (framePtsMs - VideoStream.basePtsMs) * 1_000_000L +
                VideoStream.delayMs * 1_000_000L
            if (target <= System.nanoTime()) codec.releaseOutputBuffer(idx, true)
            else codec.releaseOutputBuffer(idx, target)
        } else {
            codec.releaseOutputBuffer(idx, true)
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    // ---- minimal Annex-B NAL scanning to pull SPS (type 7) / PPS (type 8) ----

    private fun startCodeLen(d: ByteArray, i: Int): Int {
        if (i + 3 <= d.size && d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) return 3
        if (i + 4 <= d.size && d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()) return 4
        return 0
    }

    /** Returns the NAL of [type] (including its start code), or null. */
    private fun findNal(d: ByteArray, type: Int): ByteArray? {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < d.size) {
            val sc = startCodeLen(d, i)
            if (sc > 0) { starts.add(Pair(i, sc)); i += sc } else i++
        }
        for (k in starts.indices) {
            val (pos, sc) = starts[k]
            val nalStart = pos + sc
            if (nalStart >= d.size) continue
            if ((d[nalStart].toInt() and 0x1F) == type) {
                val end = if (k + 1 < starts.size) starts[k + 1].first else d.size
                return d.copyOfRange(pos, end)
            }
        }
        return null
    }
}
