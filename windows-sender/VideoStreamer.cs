using System.Buffers.Binary;
using System.Diagnostics;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;

namespace LanMediaSender;

/// <summary>
/// Captures the primary screen, H.264-encodes it, and streams it to the receiver
/// using the v3 muxed protocol. Reuses the same discovery + TLS + handshake path
/// as the audio streamer. Video-only for now; audio joins the mux in a later step.
/// </summary>
internal sealed class VideoStreamer : IStreamer
{
    public event Action<string>? Status;
    public event Action<float>? Level;    // unused for video; the meter stays at 0
    public event Action? Ended;
    public event Action<string>? Pinned;

    private const long BitRate = 10_000_000;

    private readonly string _name;
    private readonly string _ip;
    private readonly int _port;
    private readonly string _password;
    private readonly bool _useTls;
    private string _pinnedFp;
    private readonly int _fps;
    private readonly int _maxHeight;
    private readonly bool _showCursor;
    private readonly bool _includeAudio;
    private readonly int _audioBitrate;

    private volatile bool _running;
    private Thread? _netThread;
    private CancellationTokenSource? _cts;
    private ScreenH264Encoder? _enc;
    private IScreenCapture? _capture;
    private OpusAudioCapture? _audio;
    private Stopwatch? _clock;            // shared A/V timeline for the current connection
    private volatile Stream? _activeStream;
    private readonly object _writeLock = new();
    private string _captureNote = "";    // reason shown in status if GPU capture fell back

    public VideoStreamer(string name, string ip, int port, string password, bool useTls, string pinnedFp,
                         int maxHeight, int fps, bool showCursor, bool includeAudio, int audioBitrate)
    {
        _name = name ?? ""; _ip = ip; _port = port; _password = password;
        _useTls = useTls; _pinnedFp = pinnedFp ?? "";
        _maxHeight = maxHeight; _fps = fps; _showCursor = showCursor;
        _includeAudio = includeAudio; _audioBitrate = audioBitrate;
    }

    public void Start()
    {
        if (_running) return;

        // Set up capture + encoder up front so any error surfaces immediately.
        _capture = CreateCapture();
        int w = _capture.Width, h = _capture.Height;
        try
        {
            _enc = new ScreenH264Encoder(w, h, _fps, BitRate, _maxHeight);
        }
        catch
        {
            try { _capture.Dispose(); } catch { }
            _capture = null;
            throw; // surfaces in the form's Start() try/catch
        }

        _running = true;
        _cts = new CancellationTokenSource();
        _netThread = new Thread(NetworkLoop) { IsBackground = true, Name = "lan-video-net" };
        _netThread.Start();
        string scaled = (_enc.Width != w || _enc.Height != h) ? $" → {_enc.Width}x{_enc.Height}" : "";
        Status?.Invoke($"Video: {w}x{h}{scaled} @ {_fps}fps · {_capture.Name} · {_enc.EncoderName}");
    }

    /// <summary>Prefer GPU capture (DXGI); fall back to GDI if it isn't available.</summary>
    private IScreenCapture CreateCapture()
    {
        // DXGI Desktop Duplication allows only one active duplication per display.
        // After a stop/restart the previous one can take a moment to release, so
        // retry a few times before falling back to GDI.
        Exception? last = null;
        for (int attempt = 0; attempt < 4; attempt++)
        {
            try
            {
                _captureNote = "";
                return new DxgiScreenCapture(_showCursor);
            }
            catch (Exception ex)
            {
                last = ex;
                Sleep(300);
            }
        }
        _captureNote = " · GDI fallback: " + (last?.Message ?? "?");
        Status?.Invoke("GPU capture unavailable — using GDI (" + (last?.Message ?? "?") + ")");
        return new GdiScreenCapture(_showCursor);
    }

    public void Stop()
    {
        // Only signal here — the network thread owns and disposes the capture +
        // encoder when it exits, so we never free native objects it's still using.
        _running = false;
        try { _cts?.Cancel(); } catch { }
        Status?.Invoke("Stopped");
    }

    private void NetworkLoop()
    {
        try
        {
            ConnectAndStreamLoop();
        }
        finally
        {
            // Dispose on the same thread that used them — avoids a native crash
            // if Stop() races the encode/capture calls.
            try { StopAudio(); } catch { }
            try { _enc?.Dispose(); } catch { }
            try { _capture?.Dispose(); } catch { }
            _enc = null;
            _capture = null;
        }
    }

    private void ConnectAndStreamLoop()
    {
        while (_running)
        {
            TcpClient? client = null;
            Stream? stream = null;
            try
            {
                string targetIp = _ip;
                int targetPort = _port;
                if (!string.IsNullOrWhiteSpace(_name))
                {
                    Status?.Invoke($"Looking for “{_name}” on the network…");
                    var found = Discovery.Resolve(_name, 1500);
                    if (found != null) { targetIp = found.Ip; targetPort = found.Port; }
                    else if (string.IsNullOrWhiteSpace(_ip)) { Status?.Invoke($"“{_name}” not found — retrying…"); Sleep(2000); continue; }
                    else { Status?.Invoke($"“{_name}” not found — trying saved IP {_ip}"); }
                }
                if (string.IsNullOrWhiteSpace(targetIp)) { Status?.Invoke("Enter a receiver name or IP"); Sleep(2000); continue; }

                Status?.Invoke($"Connecting to {targetIp}:{targetPort} …");
                client = new TcpClient();
                var ar = client.BeginConnect(targetIp, targetPort, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(TimeSpan.FromSeconds(5))) throw new TimeoutException("connection timed out");
                client.EndConnect(ar);
                client.NoDelay = true;

                stream = client.GetStream();
                if (_useTls)
                {
                    var ssl = new SslStream(stream, false, ValidateServerCert);
                    ssl.AuthenticateAsClient(new SslClientAuthenticationOptions
                    {
                        TargetHost = "lanmedia",
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                    });
                    stream = ssl;
                }

                int w = _enc!.Width, h = _enc.Height;
                string audioJson = _includeAudio
                    ? ",\"audio\":true,\"acodec\":\"opus\",\"audioSampleRate\":48000,\"audioChannels\":2"
                    : ",\"audio\":false";
                string hello = "{\"magic\":\"" + Protocol.Magic + "\",\"version\":" + Protocol.VideoVersion +
                               ",\"auth\":\"" + Protocol.Sha256Hex(_password) + "\"," +
                               "\"video\":true,\"vcodec\":\"h264\"," +
                               "\"width\":" + w + ",\"height\":" + h + ",\"fps\":" + _fps + audioJson + "}\n";
                byte[] helloBytes = Encoding.UTF8.GetBytes(hello);
                stream.Write(helloBytes, 0, helloBytes.Length);
                stream.Flush();

                string resp = ReadLine(stream) ?? throw new IOException("no handshake reply");
                using (var doc = JsonDocument.Parse(resp))
                {
                    bool ok = doc.RootElement.TryGetProperty("ok", out var okEl) && okEl.GetBoolean();
                    if (!ok)
                    {
                        string err = doc.RootElement.TryGetProperty("error", out var eEl) ? (eEl.GetString() ?? "rejected") : "rejected";
                        Status?.Invoke("Rejected by receiver: " + err);
                        _running = false; Ended?.Invoke(); return;
                    }
                }

                Status?.Invoke($"● Streaming video to {(_name.Length > 0 ? $"“{_name}” ({targetIp})" : $"{targetIp}:{targetPort}")}"
                    + (_includeAudio ? " + audio" : "") + (_useTls ? " 🔒" : ""));

                // One shared clock for this connection so audio + video pts align.
                _clock = Stopwatch.StartNew();
                _activeStream = stream;
                StartAudio();

                PumpVideo(stream);
            }
            catch (Exception ex)
            {
                if (_running) { Status?.Invoke("Disconnected — reconnecting… (" + ex.Message + ")"); Sleep(2000); }
            }
            finally
            {
                StopAudio();
                _activeStream = null;
                try { stream?.Dispose(); } catch { }
                try { client?.Close(); } catch { }
            }
        }
    }

    private void StartAudio()
    {
        if (!_includeAudio) return;
        try
        {
            _audio = new OpusAudioCapture(_audioBitrate, WriteAudioPacket);
            _audio.Start();
        }
        catch (Exception ex)
        {
            _audio = null;
            Status?.Invoke("Audio capture unavailable: " + ex.Message);
        }
    }

    private void StopAudio()
    {
        try { _audio?.Dispose(); } catch { }
        _audio = null;
    }

    /// <summary>Called from the audio capture thread; muxes one Opus packet (type 0).</summary>
    private void WriteAudioPacket(byte[] opus)
    {
        Stream? s = _activeStream;
        Stopwatch? clk = _clock;
        if (s == null || clk == null || opus.Length == 0) return;
        long pts = clk.ElapsedMilliseconds;
        var hdr = new byte[13];
        hdr[0] = Protocol.StreamAudio;
        BinaryPrimitives.WriteInt64BigEndian(hdr.AsSpan(1, 8), pts);
        BinaryPrimitives.WriteInt32BigEndian(hdr.AsSpan(9, 4), opus.Length);
        try
        {
            lock (_writeLock)
            {
                s.Write(hdr, 0, 13);
                s.Write(opus, 0, opus.Length);
            }
        }
        catch { /* disconnect — the pump loop handles reconnect */ }
    }

    private void PumpVideo(Stream stream)
    {
        var header = new byte[13];
        Stopwatch sw = _clock!;   // shared A/V clock (started by the caller)
        long frameIntervalMs = 1000 / _fps;
        long i = 0;
        long bytes = 0;
        long frames = 0;
        double capSum = 0, encSum = 0;
        var report = Stopwatch.StartNew();

        while (_running)
        {
            long waitMs = i * frameIntervalMs - sw.ElapsedMilliseconds;
            if (waitMs > 0) Thread.Sleep((int)waitMs);

            long ptsMs = sw.ElapsedMilliseconds;

            long t0 = Stopwatch.GetTimestamp();
            bool ok = _capture!.Acquire(out IntPtr ptr, out int stride);
            long t1 = Stopwatch.GetTimestamp();
            if (!ok)
            {
                if (_capture.Lost)
                {
                    try { _capture.Dispose(); } catch { }
                    _capture = CreateCapture();
                }
                i++;
                continue;
            }

            _enc!.Encode(ptr, stride, i, payload =>
            {
                header[0] = Protocol.StreamVideo;
                BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(1, 8), ptsMs);
                BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(9, 4), payload.Length);
                lock (_writeLock)   // audio thread writes to the same stream
                {
                    stream.Write(header, 0, 13);
                    stream.Write(payload, 0, payload.Length);
                }
                bytes += payload.Length + 13;
            });
            _capture.Release();
            long t2 = Stopwatch.GetTimestamp();

            double f = 1000.0 / Stopwatch.Frequency;
            capSum += (t1 - t0) * f;
            encSum += (t2 - t1) * f;
            i++;
            frames++;

            if (report.ElapsedMilliseconds >= 1000)
            {
                double secs = report.Elapsed.TotalSeconds;
                double mbit = bytes * 8.0 / 1_000_000 / secs;
                double fps = frames / secs;
                Status?.Invoke(
                    $"● {_enc.Width}x{_enc.Height} · {_enc.EncoderName}/{_capture!.Name} · {fps:F0}fps · " +
                    $"cap {capSum / frames:F1}ms/enc {encSum / frames:F1}ms · {mbit:F1} Mbit/s" +
                    (_useTls ? " 🔒" : "") + _captureNote);
                bytes = 0; frames = 0; capSum = 0; encSum = 0; report.Restart();
            }
        }
    }

    private bool ValidateServerCert(object sender, X509Certificate? cert, X509Chain? chain, SslPolicyErrors errors)
    {
        if (cert == null) return false;
        using var c2 = new X509Certificate2(cert);
        byte[] hash = c2.GetCertHash(HashAlgorithmName.SHA256);
        string fp = string.Join(":", hash.Select(x => x.ToString("X2")));
        if (string.IsNullOrEmpty(_pinnedFp)) { _pinnedFp = fp; Pinned?.Invoke(fp); return true; }
        return string.Equals(_pinnedFp.Replace(":", ""), fp.Replace(":", ""), StringComparison.OrdinalIgnoreCase);
    }

    private static void Sleep(int ms) { try { Thread.Sleep(ms); } catch { } }

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
}
