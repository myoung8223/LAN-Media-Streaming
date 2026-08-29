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

    /** How far behind "live" we present, to absorb jitter and match the audio prime. */
    const val DELAY_MS = 150L

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
