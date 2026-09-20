package com.lanmedia.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 encoder fed by a VirtualDisplay through an input Surface. Emits Annex-B
 * access units; SPS/PPS (the codec-config data) are prepended to each keyframe so
 * the receivers — which scan keyframes for SPS — can always configure their
 * decoders. This mirrors the Windows sender's dump_extra behavior.
 */
class VideoEncoder(width: Int, height: Int, fps: Int, bitRateBps: Int) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private var csd: ByteArray? = null   // SPS+PPS, captured once

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // a keyframe every ~1s
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (_: Exception) { /* some encoders reject this; harmless */ }
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() = codec.start()

    /**
     * Pull encoded frames until [running] goes false, handing each complete access
     * unit to [sink]. Blocks; run on its own thread.
     */
    fun drainInto(running: () -> Boolean, sink: (ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (running()) {
            val outIdx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val sps = codec.outputFormat.getByteBuffer("csd-0")   // AVC: SPS+PPS together
                if (sps != null) { val b = ByteArray(sps.remaining()); sps.get(b); csd = b }
                continue
            }
            if (outIdx < 0) continue   // INFO_TRY_AGAIN_LATER, etc.

            val buf = codec.getOutputBuffer(outIdx)
            if (buf == null) { codec.releaseOutputBuffer(outIdx, false); continue }

            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // Config-only buffer: capture SPS/PPS, don't send it as a frame.
                val c = ByteArray(info.size)
                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(c)
                csd = c
                codec.releaseOutputBuffer(outIdx, false)
                continue
            }

            if (info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val frame = ByteArray(info.size); buf.get(frame)
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                val cc = csd
                val payload = if (isKey && cc != null && !beginsWithParamSet(frame)) {
                    ByteArray(cc.size + frame.size).also {
                        System.arraycopy(cc, 0, it, 0, cc.size)
                        System.arraycopy(frame, 0, it, cc.size, frame.size)
                    }
                } else frame
                sink(payload)
            }

            codec.releaseOutputBuffer(outIdx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
    }

    fun stop() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
    }

    /** True if the access unit already starts with an SPS (7) or PPS (8) NAL. */
    private fun beginsWithParamSet(b: ByteArray): Boolean {
        var i = when {
            b.size >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 &&
                b[2].toInt() == 0 && b[3].toInt() == 1 -> 4
            b.size >= 3 && b[0].toInt() == 0 && b[1].toInt() == 0 &&
                b[2].toInt() == 1 -> 3
            else -> return false
        }
        if (i >= b.size) return false
        val t = b[i].toInt() and 0x1F
        return t == 7 || t == 8
    }
}
