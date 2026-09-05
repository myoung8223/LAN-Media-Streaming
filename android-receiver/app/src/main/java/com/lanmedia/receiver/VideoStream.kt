package com.lanmedia.receiver

import java.util.concurrent.LinkedBlockingQueue

/**
 * Hand-off between the network service (reads H.264 access units + audio off the
 * socket) and VideoActivity (decodes + displays video). Also holds the shared
 * A/V timeline so video can be scheduled to line up with audio.
 */
object VideoStream {

    /** One H.264 access unit with its sender timestamp. */
    class Packet(val ptsMs: Long, val data: ByteArray)

    @Volatile var active = false
        private set
    @Volatile var width = 1920
        private set
    @Volatile var height = 1080
        private set
    @Volatile var hasAudio = false
        private set

    /** Playout timeline: local nanoTime that corresponds to [basePtsMs]. 0 = not set yet. */
    @Volatile var baseNanos = 0L
    @Volatile var basePtsMs = 0L

    /** Effective playout buffer actually in force for the current stream: how far
     *  behind "live" we present, to absorb jitter and match the audio prime. This is
     *  [requestedDelayMs] clamped up to the fps-derived floor (see applyDelayForFps),
     *  so it is never below roughly one frame interval. Read by the video schedule
     *  and the audio prime. */
    @Volatile var delayMs = 150L

    /** The user's requested playout buffer (the "Playout buffer (ms)" field), in ms,
     *  before the per-stream frame-rate floor is applied. Default is the Wi-Fi-safe
     *  value. Set before a stream begins; the effective [delayMs] is derived from it. */
    @Volatile var requestedDelayMs = 150L

    /**
     * Smallest sensible buffer for a given frame rate: about 1.2 frame intervals, so
     * it stays just above one frame (below that the renderer has no frame to show and
     * stutters regardless of the network). 30fps → 40ms, 60fps → 20ms. Never returns
     * less than 20ms — the practical floor once residual jitter is accounted for.
     */
    fun floorMsForFps(fps: Int): Long {
        val f = if (fps in 1..240) fps else 30
        return Math.round(1.2 * 1000.0 / f).coerceAtLeast(20L)
    }

    /** Apply the requested buffer for a stream at [fps], clamped up to the frame floor. */
    fun applyDelayForFps(fps: Int) {
        delayMs = maxOf(requestedDelayMs, floorMsForFps(fps))
    }

    // ~2s of 30fps buffered; drop the oldest under backpressure.
    val queue = LinkedBlockingQueue<Packet>(120)

    fun begin(w: Int, h: Int, audio: Boolean) {
        width = if (w > 0) w else 1920
        height = if (h > 0) h else 1080
        hasAudio = audio
        baseNanos = 0L
        basePtsMs = 0L
        queue.clear()
        active = true
    }

    /** Anchor the timeline to the first packet seen (audio or video). */
    fun noteBase(ptsMs: Long) {
        if (baseNanos == 0L) {
            basePtsMs = ptsMs
            baseNanos = System.nanoTime()
        }
    }

    fun pushVideo(ptsMs: Long, data: ByteArray) {
        val p = Packet(ptsMs, data)
        if (!queue.offer(p)) {
            queue.poll()
            queue.offer(p)
        }
    }

    fun end() {
        active = false
    }
}
