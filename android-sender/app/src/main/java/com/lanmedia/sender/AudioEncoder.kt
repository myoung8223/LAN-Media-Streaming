package com.lanmedia.sender

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Captures the device's playback audio (AudioPlaybackCapture, API 29+) and encodes
 * it to Opus with MediaCodec. Construction throws if system-audio capture or an
 * Opus encoder isn't available on the device — the service then streams video only.
 * The whole class requires API 29; the service only instantiates it on API 29+.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")   // the service ensures RECORD_AUDIO is granted first
class AudioEncoder(projection: MediaProjection, bitRateBps: Int) {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
    }

    private val record: AudioRecord
    private val codec: MediaCodec
    private val minBuf: Int
    private var samplesRead = 0L

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        minBuf = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            (SAMPLE_RATE * CHANNELS * 2) / 10, // ~100 ms floor
        )

        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord (playback capture) failed to initialize")
        }

        val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }
        // Throws if the device has no Opus encoder; the caller falls back to video-only.
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        record.startRecording()
    }

    /**
     * Feed captured PCM into the encoder and hand each Opus packet to [sink] until
     * [running] goes false. Blocks; run on its own thread.
     */
    fun loop(running: () -> Boolean, sink: (ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (running()) {
            val inIdx = try { codec.dequeueInputBuffer(10_000) } catch (_: Exception) { break }
            if (inIdx >= 0) {
                val ib: ByteBuffer? = codec.getInputBuffer(inIdx)
                var n = 0
                if (ib != null) {
                    ib.clear()
                    n = record.read(ib, minOf(ib.capacity(), minBuf))
                    if (n < 0) n = 0
                }
                val ptsUs = samplesRead * 1_000_000L / SAMPLE_RATE
                if (n > 0) samplesRead += (n / (CHANNELS * 2)).toLong()
                codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
            }

            var outIdx = try { codec.dequeueOutputBuffer(info, 0) } catch (_: Exception) { break }
            while (outIdx >= 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val pkt = ByteArray(info.size); ob.get(pkt)
                        sink(pkt)
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                outIdx = try { codec.dequeueOutputBuffer(info, 0) } catch (_: Exception) { -1 }
            }
        }
    }

    fun stop() {
        try { record.stop() } catch (_: Exception) {}
        try { record.release() } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
