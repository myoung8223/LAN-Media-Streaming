using System.Collections.Generic;
using Concentus.Enums;
using Concentus.Structs;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace LanMediaSender;

/// <summary>
/// Captures system audio (WASAPI loopback), resamples to 48 kHz stereo, and
/// encodes 20 ms Opus frames — invoking a callback per packet. Used to mux audio
/// into the video stream. Self-contained so the working AudioStreamer is untouched.
/// </summary>
internal sealed class OpusAudioCapture : IDisposable
{
    public const int Rate = 48000;
    public const int Channels = 2;
    public const int FrameSamples = 960;                       // 20 ms @ 48 kHz
    private const int FrameBytes = FrameSamples * Channels * 2; // S16LE = 3840

    private readonly Action<byte[]> _onPacket;
    private readonly WasapiLoopbackCapture _cap;
    private readonly OpusEncoder _enc;
    private readonly StereoLinearResampler? _resampler;
    private readonly List<byte> _acc = new(FrameBytes * 4);
    private readonly short[] _shortFrame = new short[FrameSamples * Channels];
    private readonly byte[] _packet = new byte[4000];
    private readonly object _encLock = new();
    private volatile bool _running;

    public OpusAudioCapture(int bitrate, Action<byte[]> onPacket)
    {
        _onPacket = onPacket;
        _enc = OpusEncoder.Create(Rate, Channels, OpusApplication.OPUS_APPLICATION_AUDIO);
        _enc.Bitrate = bitrate;
        _enc.UseVBR = true;
        _enc.UseDTX = false; // steady packet cadence
        _cap = new WasapiLoopbackCapture();
        int nativeRate = _cap.WaveFormat.SampleRate;
        _resampler = nativeRate != Rate ? new StereoLinearResampler(nativeRate, Rate) : null;
        _cap.DataAvailable += OnData;
    }

    public void Start() { _running = true; _cap.StartRecording(); }

    public void Stop()
    {
        _running = false;
        try { _cap.StopRecording(); } catch { }
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || e.BytesRecorded <= 0) return;
        float[]? stereo = ExtractStereoFloat(e.Buffer, e.BytesRecorded, _cap.WaveFormat);
        if (stereo == null || stereo.Length == 0) return;
        if (_resampler != null) stereo = _resampler.Process(stereo);
        if (stereo.Length == 0) return;

        lock (_encLock)
        {
            // append as S16LE
            int frames = stereo.Length / 2;
            for (int i = 0; i < frames; i++)
            {
                short sl = ToShort(stereo[i * 2]);
                short sr = ToShort(stereo[i * 2 + 1]);
                _acc.Add((byte)(sl & 0xff)); _acc.Add((byte)((sl >> 8) & 0xff));
                _acc.Add((byte)(sr & 0xff)); _acc.Add((byte)((sr >> 8) & 0xff));
            }

            while (_acc.Count >= FrameBytes)
            {
                for (int i = 0; i < _shortFrame.Length; i++)
                {
                    int b = i * 2;
                    _shortFrame[i] = (short)(_acc[b] | (_acc[b + 1] << 8));
                }
                _acc.RemoveRange(0, FrameBytes);

                int n = _enc.Encode(_shortFrame, 0, FrameSamples, _packet, 0, _packet.Length);
                if (n <= 0) continue;
                var outBuf = new byte[n];
                System.Buffer.BlockCopy(_packet, 0, outBuf, 0, n);
                try { _onPacket(outBuf); } catch { /* stream write may fail on disconnect */ }
            }
        }
    }

    public void Dispose()
    {
        Stop();
        try { _cap.Dispose(); } catch { }
    }

    private static short ToShort(float f)
    {
        float v = f < -1f ? -1f : (f > 1f ? 1f : f);
        return (short)(v * 32767f);
    }

    private static float[]? ExtractStereoFloat(byte[] buffer, int bytes, WaveFormat fmt)
    {
        int inCh = fmt.Channels;
        if (inCh < 1) return null;

        if (fmt.Encoding == WaveFormatEncoding.IeeeFloat && fmt.BitsPerSample == 32)
        {
            int frames = bytes / (4 * inCh);
            var f = new float[bytes / 4];
            Buffer.BlockCopy(buffer, 0, f, 0, frames * 4 * inCh);
            var outF = new float[frames * 2];
            for (int i = 0; i < frames; i++)
            {
                float l = f[i * inCh];
                float r = inCh > 1 ? f[i * inCh + 1] : l;
                outF[i * 2] = l; outF[i * 2 + 1] = r;
            }
            return outF;
        }
        if (fmt.Encoding == WaveFormatEncoding.Pcm && fmt.BitsPerSample == 16)
        {
            int frames = bytes / (2 * inCh);
            var outF = new float[frames * 2];
            for (int i = 0; i < frames; i++)
            {
                int baseIdx = i * inCh * 2;
                short l = (short)(buffer[baseIdx] | (buffer[baseIdx + 1] << 8));
                short r = inCh > 1 ? (short)(buffer[baseIdx + 2] | (buffer[baseIdx + 3] << 8)) : l;
                outF[i * 2] = l / 32768f; outF[i * 2 + 1] = r / 32768f;
            }
            return outF;
        }
        return null;
    }

    /// <summary>Streaming linear resampler for interleaved stereo float (native → 48 kHz).</summary>
    private sealed class StereoLinearResampler
    {
        private readonly double _step;
        private double _pos;
        private float _histL, _histR;
        private bool _hasHist;

        public StereoLinearResampler(int inRate, int outRate) { _step = (double)inRate / outRate; }

        public float[] Process(float[] inp)
        {
            int n = inp.Length / 2;
            if (n == 0) return Array.Empty<float>();
            var outList = new List<float>((int)(n / _step) * 2 + 4);
            double pos = _pos;
            while (pos < n - 1)
            {
                int i = (int)Math.Floor(pos);
                double frac = pos - i;
                float l0, r0;
                if (i < 0) { l0 = _hasHist ? _histL : inp[0]; r0 = _hasHist ? _histR : inp[1]; }
                else { l0 = inp[i * 2]; r0 = inp[i * 2 + 1]; }
                float l1 = inp[(i + 1) * 2], r1 = inp[(i + 1) * 2 + 1];
                outList.Add((float)(l0 + (l1 - l0) * frac));
                outList.Add((float)(r0 + (r1 - r0) * frac));
                pos += _step;
            }
            _pos = pos - n;
            _histL = inp[(n - 1) * 2];
            _histR = inp[(n - 1) * 2 + 1];
            _hasHist = true;
            return outList.ToArray();
        }
    }
}
