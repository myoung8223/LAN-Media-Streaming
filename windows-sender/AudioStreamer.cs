using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Concentus.Enums;
using Concentus.Structs;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace LanMediaSender;

/// <summary>
/// Captures Windows audio (system loopback or mic), converts it to interleaved
/// stereo signed-16 PCM, and streams it to the Android receiver over TCP using
/// protocol v2. Audio is sent either as raw PCM or Opus-compressed, negotiated
/// in the handshake. Auto-reconnects if the link drops.
/// </summary>
internal class AudioStreamer : IStreamer
{
    public event Action<string>? Status;
    public event Action<float>? Level; // 0..1 peak
    public event Action? Ended;        // fired if streaming stops on its own (e.g. rejected)
    public event Action<string>? Pinned; // fired with the cert fingerprint on trust-on-first-use

    // Opus is always run at 48 kHz stereo, 20 ms frames.
    private const int OpusRate = 48000;
    private const int OpusChannels = 2;
    private const int OpusFrameSamples = 960;               // 20 ms @ 48 kHz, per channel
    private const int OpusFrameBytes = OpusFrameSamples * OpusChannels * 2; // S16LE = 3840

    private readonly string _name;   // panel name to resolve by (empty = use IP directly)
    private readonly string _ip;
    private readonly int _port;
    private readonly string _password;
    private readonly bool _system; // true = loopback, false = mic
    private readonly bool _useTls;
    private readonly bool _useOpus;
    private readonly int _bitrate;
    private string _pinnedFp;      // expected cert fingerprint ("" = trust first, then pin)

    private IWaveIn? _capture;
    private int _sampleRate = 48000;   // native capture rate
    private int _outRate = 48000;      // rate of the PCM we enqueue (48k for Opus)
    private StereoLinearResampler? _resampler;
    private volatile bool _running;
    private Thread? _netThread;
    private CancellationTokenSource? _cts;
    private BlockingCollection<byte[]>? _queue;

    public AudioStreamer(string name, string ip, int port, string password, bool system,
                         bool useTls, string pinnedFp, bool useOpus, int bitrate)
    {
        _name = name ?? ""; _ip = ip; _port = port; _password = password; _system = system;
        _useTls = useTls; _pinnedFp = pinnedFp ?? "";
        _useOpus = useOpus; _bitrate = bitrate;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;
        _cts = new CancellationTokenSource();
        _queue = new BlockingCollection<byte[]>(boundedCapacity: 64);

        _capture = _system ? new WasapiLoopbackCapture() : new WasapiCapture();
        _sampleRate = _capture.WaveFormat.SampleRate;

        if (_useOpus)
        {
            _outRate = OpusRate;
            _resampler = _sampleRate != OpusRate
                ? new StereoLinearResampler(_sampleRate, OpusRate)
                : null;
        }
        else
        {
            _outRate = _sampleRate; // PCM streams at the native rate, as before
            _resampler = null;
        }

        _capture.DataAvailable += OnData;
        _capture.RecordingStopped += (_, __) => { };
        _capture.StartRecording();

        _netThread = new Thread(NetworkLoop) { IsBackground = true, Name = "lan-audio-net" };
        _netThread.Start();

        string rateNote = (_useOpus && _resampler != null) ? $" (resampled {_sampleRate}→48000)" : "";
        Status?.Invoke($"Capturing {( _system ? "system audio" : "microphone")} @ {_sampleRate} Hz{rateNote}");
    }

    public void Stop()
    {
        _running = false;
        try { _cts?.Cancel(); } catch { }
        try { _capture?.StopRecording(); } catch { }
        try { _capture?.Dispose(); } catch { }
        _capture = null;
        Status?.Invoke("Stopped");
        Level?.Invoke(0f);
    }

    // ---- capture -> convert -> enqueue ----
    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (!_running || _queue == null) return;
        var fmt = _capture!.WaveFormat;

        // Loopback capture fires empty buffers during silence — skip them.
        if (e.BytesRecorded <= 0) return;

        float[]? stereo = ExtractStereoFloat(e.Buffer, e.BytesRecorded, fmt, out float peak);
        if (stereo == null || stereo.Length == 0) return;
        Level?.Invoke(peak);

        if (_resampler != null) stereo = _resampler.Process(stereo);
        if (stereo.Length == 0) return;

        byte[] pcm = FloatToS16LE(stereo);
        if (pcm.Length == 0) return;

        // enqueue with drop-oldest so latency can't build up if the network stalls
        if (!_queue.TryAdd(pcm))
        {
            _queue.TryTake(out _);
            _queue.TryAdd(pcm);
        }
    }

    private static short ToShort(float f)
    {
        float v = f < -1f ? -1f : (f > 1f ? 1f : f);
        return (short)(v * 32767f);
    }

    /// <summary>Decode any supported capture buffer to interleaved stereo float [-1,1]. Returns peak.</summary>
    private static float[]? ExtractStereoFloat(byte[] buffer, int bytes, WaveFormat fmt, out float peak)
    {
        peak = 0f;
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
                float p = Math.Max(Math.Abs(l), Math.Abs(r));
                if (p > peak) peak = p;
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
                short r = inCh > 1
                    ? (short)(buffer[baseIdx + 2] | (buffer[baseIdx + 3] << 8))
                    : l;
                float lf = l / 32768f, rf = r / 32768f;
                float p = Math.Max(Math.Abs(lf), Math.Abs(rf));
                if (p > peak) peak = p;
                outF[i * 2] = lf; outF[i * 2 + 1] = rf;
            }
            return outF;
        }

        return null; // unsupported capture format
    }

    /// <summary>Interleaved stereo float -> interleaved stereo S16LE bytes.</summary>
    private static byte[] FloatToS16LE(float[] stereo)
    {
        int frames = stereo.Length / 2;
        var outBytes = new byte[frames * 4];
        int o = 0;
        for (int i = 0; i < frames; i++)
        {
            short sl = ToShort(stereo[i * 2]);
            short sr = ToShort(stereo[i * 2 + 1]);
            outBytes[o++] = (byte)(sl & 0xff); outBytes[o++] = (byte)((sl >> 8) & 0xff);
            outBytes[o++] = (byte)(sr & 0xff); outBytes[o++] = (byte)((sr >> 8) & 0xff);
        }
        return outBytes;
    }

    // ---- network: connect -> handshake -> stream, with reconnect ----
    private void NetworkLoop()
    {
        while (_running)
        {
            TcpClient? client = null;
            Stream? stream = null;
            OpusEncoder? encoder = null;
            try
            {
                // Resolve the target: prefer discovery-by-name (DHCP-proof), fall
                // back to the manually entered IP if no panel answers.
                string targetIp = _ip;
                int targetPort = _port;
                if (!string.IsNullOrWhiteSpace(_name))
                {
                    Status?.Invoke($"Looking for “{_name}” on the network…");
                    var found = Discovery.Resolve(_name, 1500);
                    if (found != null)
                    {
                        targetIp = found.Ip;
                        targetPort = found.Port;
                    }
                    else if (string.IsNullOrWhiteSpace(_ip))
                    {
                        Status?.Invoke($"“{_name}” not found on the network — retrying…");
                        try { Thread.Sleep(2000); } catch { }
                        continue;
                    }
                    else
                    {
                        Status?.Invoke($"“{_name}” not found — trying saved IP {_ip}");
                    }
                }

                if (string.IsNullOrWhiteSpace(targetIp))
                {
                    Status?.Invoke("Enter a panel name or an IP address");
                    try { Thread.Sleep(2000); } catch { }
                    continue;
                }

                Status?.Invoke($"Connecting to {targetIp}:{targetPort} …");
                client = new TcpClient();
                var ar = client.BeginConnect(targetIp, targetPort, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(TimeSpan.FromSeconds(5)))
                    throw new TimeoutException("connection timed out");
                client.EndConnect(ar);
                client.NoDelay = true;

                stream = client.GetStream();
                if (_useTls)
                {
                    var ssl = new SslStream(stream, leaveInnerStreamOpen: false, ValidateServerCert);
                    ssl.AuthenticateAsClient(new SslClientAuthenticationOptions
                    {
                        TargetHost = "lanmedia",
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                    });
                    stream = ssl;
                }

                // handshake — negotiate codec
                int hsRate = _useOpus ? OpusRate : _sampleRate;
                string codec = _useOpus ? "opus" : "pcm";
                string hello = "{\"magic\":\"" + Protocol.Magic + "\",\"version\":" + Protocol.Version +
                               ",\"auth\":\"" + Protocol.Sha256Hex(_password) + "\"," +
                               "\"sampleRate\":" + hsRate + ",\"channels\":2,\"bits\":16," +
                               "\"codec\":\"" + codec + "\",\"frameSize\":" + OpusFrameSamples + "}\n";
                byte[] helloBytes = Encoding.UTF8.GetBytes(hello);
                stream.Write(helloBytes, 0, helloBytes.Length);
                stream.Flush();

                string resp = ReadLine(stream) ?? throw new IOException("no handshake reply");
                using (var doc = JsonDocument.Parse(resp))
                {
                    bool ok = doc.RootElement.TryGetProperty("ok", out var okEl) && okEl.GetBoolean();
                    if (!ok)
                    {
                        string err = doc.RootElement.TryGetProperty("error", out var eEl)
                            ? (eEl.GetString() ?? "rejected") : "rejected";
                        Status?.Invoke("Rejected by receiver: " + err);
                        // A rejection (e.g. wrong password) won't fix itself — stop retrying.
                        _running = false;
                        try { _capture?.StopRecording(); } catch { }
                        Ended?.Invoke();
                        return;
                    }
                }

                string dest = string.IsNullOrWhiteSpace(_name) ? $"{targetIp}:{targetPort}"
                                                               : $"“{_name}” ({targetIp})";
                Status?.Invoke($"● Streaming to {dest}"
                    + (_useOpus ? $" · Opus {_bitrate / 1000}k" : " · PCM")
                    + (_useTls ? " 🔒" : ""));

                var token = _cts!.Token;
                if (_useOpus)
                {
                    encoder = OpusEncoder.Create(OpusRate, OpusChannels, OpusApplication.OPUS_APPLICATION_AUDIO);
                    encoder.Bitrate = _bitrate;
                    encoder.UseVBR = true;
                    encoder.UseDTX = false; // keep a steady packet cadence; no zero-length frames
                    StreamOpus(stream, encoder, token);
                }
                else
                {
                    StreamPcm(stream, token);
                }
            }
            catch (Exception ex)
            {
                if (_running)
                {
                    Status?.Invoke("Disconnected — reconnecting… (" + ex.Message + ")");
                    try { Thread.Sleep(2000); } catch { }
                }
            }
            finally
            {
                try { stream?.Dispose(); } catch { }
                try { client?.Close(); } catch { }
            }
        }
    }

    /// <summary>Raw PCM path: write each converted chunk straight to the stream.</summary>
    private void StreamPcm(Stream stream, CancellationToken token)
    {
        while (_running)
        {
            byte[] chunk;
            try { chunk = _queue!.Take(token); }
            catch (OperationCanceledException) { break; }
            if (chunk.Length == 0) continue; // never emit an empty (TLS) record
            stream.Write(chunk, 0, chunk.Length);
        }
    }

    /// <summary>
    /// Opus path: accumulate the enqueued 48 kHz PCM into exact 20 ms frames,
    /// encode each, and write it length-prefixed: [2-byte big-endian len][packet].
    /// </summary>
    private void StreamOpus(Stream stream, OpusEncoder encoder, CancellationToken token)
    {
        var acc = new List<byte>(OpusFrameBytes * 4);
        var shortFrame = new short[OpusFrameSamples * OpusChannels]; // 1920
        var packet = new byte[4000];

        while (_running)
        {
            byte[] chunk;
            try { chunk = _queue!.Take(token); }
            catch (OperationCanceledException) { break; }
            if (chunk.Length == 0) continue;
            acc.AddRange(chunk);

            while (acc.Count >= OpusFrameBytes)
            {
                for (int i = 0; i < shortFrame.Length; i++)
                {
                    int b = i * 2;
                    shortFrame[i] = (short)(acc[b] | (acc[b + 1] << 8));
                }
                acc.RemoveRange(0, OpusFrameBytes);

                int n = encoder.Encode(shortFrame, 0, OpusFrameSamples, packet, 0, packet.Length);
                if (n <= 0) continue; // never emit an empty record

                var outFrame = new byte[2 + n];
                outFrame[0] = (byte)((n >> 8) & 0xff);
                outFrame[1] = (byte)(n & 0xff);
                Buffer.BlockCopy(packet, 0, outFrame, 2, n);
                stream.Write(outFrame, 0, outFrame.Length);
            }
        }
    }

    /// <summary>Pin the receiver's self-signed cert by SHA-256 fingerprint (trust-on-first-use).</summary>
    private bool ValidateServerCert(object sender, X509Certificate? cert, X509Chain? chain, SslPolicyErrors errors)
    {
        if (cert == null) return false;
        using var c2 = new X509Certificate2(cert);
        byte[] hash = c2.GetCertHash(HashAlgorithmName.SHA256);
        string fp = string.Join(":", hash.Select(b => b.ToString("X2")));
        if (string.IsNullOrEmpty(_pinnedFp))
        {
            _pinnedFp = fp;
            Pinned?.Invoke(fp); // first time — remember it
            return true;
        }
        return string.Equals(_pinnedFp.Replace(":", ""), fp.Replace(":", ""),
            StringComparison.OrdinalIgnoreCase);
    }

    private static string? ReadLine(Stream s, int maxLen = 8192)
    {
        var sb = new StringBuilder();
        while (true)
        {
            int c = s.ReadByte();
            if (c == -1) return sb.Length == 0 ? null : sb.ToString();
            if (c == '\n') return sb.ToString();
            if (c != '\r') sb.Append((char)c);
            if (sb.Length > maxLen) return sb.ToString();
        }
    }

    /// <summary>
    /// Streaming linear resampler for interleaved stereo float. Only used when the
    /// Windows mixer rate isn't 48 kHz (Opus requires 48/24/16/12/8 kHz). Keeps a
    /// one-frame history and a fractional position so it is continuous across the
    /// variable-sized capture buffers. Best quality is at a native 48 kHz mixer.
    /// </summary>
    private sealed class StereoLinearResampler
    {
        private readonly double _step;   // input frames advanced per output sample
        private double _pos;             // fractional input index of next output, in current-buffer coords
        private float _histL, _histR;    // last input frame of the previous buffer (index -1)
        private bool _hasHist;

        public StereoLinearResampler(int inRate, int outRate)
        {
            _step = (double)inRate / outRate;
            _pos = 0.0;
        }

        public float[] Process(float[] inp)
        {
            int n = inp.Length / 2;
            if (n == 0) return System.Array.Empty<float>();

            var outList = new List<float>((int)(n / _step) * 2 + 4);
            double pos = _pos;

            // Emit output samples while both neighbours (floor(pos), floor(pos)+1)
            // are available. floor(pos) may be -1 (the history frame).
            while (pos < n - 1)
            {
                int i = (int)Math.Floor(pos);
                double frac = pos - i;

                float l0, r0, l1, r1;
                if (i < 0) { l0 = _hasHist ? _histL : inp[0]; r0 = _hasHist ? _histR : inp[1]; }
                else { l0 = inp[i * 2]; r0 = inp[i * 2 + 1]; }
                l1 = inp[(i + 1) * 2]; r1 = inp[(i + 1) * 2 + 1];

                outList.Add((float)(l0 + (l1 - l0) * frac));
                outList.Add((float)(r0 + (r1 - r0) * frac));
                pos += _step;
            }

            _pos = pos - n;                  // carry fractional position into next buffer
            _histL = inp[(n - 1) * 2];
            _histR = inp[(n - 1) * 2 + 1];
            _hasHist = true;
            return outList.ToArray();
        }
    }
}
