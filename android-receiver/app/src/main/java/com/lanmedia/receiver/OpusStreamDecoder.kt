package com.lanmedia.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a stream of raw Opus packets to interleaved S16LE PCM using Android's
 * built-in "audio/opus" MediaCodec decoder — no third-party library.
 *
 * The decoder needs three pieces of codec-specific data, exactly as ExoPlayer
 * supplies them for WebM/Matroska Opus:
 *   csd-0 = the RFC 7845 "OpusHead" identification header
 *   csd-1 = codec delay (pre-skip) in nanoseconds, native byte order (int64)
 *   csd-2 = seek pre-roll in nanoseconds, native byte order (int64)
 *
 * Fixed at 48 kHz stereo, which is what the sender always negotiates for Opus.
 */
class OpusStreamDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val preSkipSamples = 3840L                 // matches OpusHead below (ExoPlayer default)
        val preSkipNs = preSkipSamples * 1_000_000_000L / 48_000L
        val seekPreRollNs = 3840L * 1_000_000_000L / 48_000L

        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(channels, preSkipSamples.toInt(), sampleRate)))
            setByteBuffer("csd-1", ByteBuffer.wrap(longNative(preSkipNs)))
            setByteBuffer("csd-2", ByteBuffer.wrap(longNative(seekPreRollNs)))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /**
     * Feed one Opus packet, return whatever PCM is now available (may be empty,
     * e.g. while the decoder is still trimming pre-skip at the very start).
     */
    fun decode(packet: ByteArray): ByteArray {
        val inIdx = codec.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            val ib = codec.getInputBuffer(inIdx)!!
            ib.clear()
            ib.put(packet)
            codec.queueInputBuffer(inIdx, 0, packet.size, ptsUs, 0)
            ptsUs += 20_000L // 20 ms per frame
        }

        val out = ByteArrayOutputStream(4096)
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx < 0) break
            val ob = codec.getOutputBuffer(outIdx)
            if (ob != null && bufferInfo.size > 0) {
                val pcm = ByteArray(bufferInfo.size)
                ob.position(bufferInfo.offset)
                ob.get(pcm)
                out.write(pcm)
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
        return out.toByteArray()
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private fun opusHead(channels: Int, preSkip: Int, inputSampleRate: Int): ByteArray {
        // RFC 7845 §5.1, mapping family 0. 19 bytes for mono/stereo.
        val bb = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OpusHead".toByteArray(Charsets.US_ASCII)) // 8
        bb.put(1)                                          // version
        bb.put(channels.toByte())                          // channel count
        bb.putShort(preSkip.toShort())                     // pre-skip (LE)
        bb.putInt(inputSampleRate)                         // original sample rate (LE)
        bb.putShort(0)                                     // output gain (LE)
        bb.put(0)                                          // mapping family 0
        return bb.array()
    }

    private fun longNative(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(v).array()

    companion object {
        private const val MIME = "audio/opus"
    }
}
