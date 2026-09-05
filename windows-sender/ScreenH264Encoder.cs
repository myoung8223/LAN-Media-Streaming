using FFmpeg.AutoGen;

namespace LanMediaSender;

/// <summary>
/// Encodes externally-supplied BGRA frames to H.264. Prefers a hardware encoder
/// (AMD AMF, then Intel Quick Sync) and falls back to software libx264. Output is
/// downscaled to fit a max height. Capture is decoupled — the caller (an
/// IScreenCapture) hands it a BGRA pointer per frame. Requires the FFmpeg 7.1
/// "shared" DLLs next to the exe (loaded lazily).
/// </summary>
internal sealed unsafe class ScreenH264Encoder : IDisposable
{
    private readonly int _srcW, _srcH, _outW, _outH;
    private AVCodecContext* _c;
    private AVFrame* _frame;
    private AVPacket* _pkt;
    private SwsContext* _sws;
    private AVBSFContext* _bsf;   // dump_extra: forces SPS/PPS in-band on every keyframe
    private static bool _ffmpegInit;

    public int Width => _outW;
    public int Height => _outH;
    public string EncoderName { get; private set; } = "?";

    public ScreenH264Encoder(int srcWidth, int srcHeight, int fps, long bitRate, int maxWidth, int maxHeight)
    {
        _srcW = srcWidth; _srcH = srcHeight;
        // Fit the source inside the maxWidth x maxHeight box, preserving aspect
        // ratio, and never upscale (scale is capped at 1.0). So a user-supplied
        // resolution acts as a bounding box, not a forced stretch — a mismatched
        // aspect ratio letterboxes by shrinking to fit rather than distorting.
        double scale = Math.Min(1.0, Math.Min((double)maxWidth / srcWidth, (double)maxHeight / srcHeight));
        _outW = ((int)Math.Round(srcWidth * scale)) & ~1;
        _outH = ((int)Math.Round(srcHeight * scale)) & ~1;

        if (!_ffmpegInit)
        {
            ffmpeg.RootPath = AppContext.BaseDirectory;
            _ = ffmpeg.avcodec_version(); // throws here if the DLLs are missing
            _ffmpegInit = true;
        }

        AVPixelFormat fmt = OpenBestEncoder(fps, bitRate);
        InitDumpExtraBsf();

        _frame = ffmpeg.av_frame_alloc();
        _frame->format = (int)fmt;
        _frame->width = _outW;
        _frame->height = _outH;
        ffmpeg.av_frame_get_buffer(_frame, 32);
        _pkt = ffmpeg.av_packet_alloc();

        _sws = ffmpeg.sws_getContext(
            _srcW, _srcH, AVPixelFormat.AV_PIX_FMT_BGRA,
            _outW, _outH, fmt, ffmpeg.SWS_FAST_BILINEAR, null, null, null);
        if (_sws == null) throw new ApplicationException("sws_getContext failed.");
    }

    private AVPixelFormat OpenBestEncoder(int fps, long bitRate)
    {
        var candidates = new (string name, AVPixelFormat fmt)[]
        {
            ("h264_amf", AVPixelFormat.AV_PIX_FMT_NV12),   // AMD
            ("h264_qsv", AVPixelFormat.AV_PIX_FMT_NV12),   // Intel
            ("libx264",  AVPixelFormat.AV_PIX_FMT_YUV420P) // software fallback
        };
        foreach (var (name, fmt) in candidates)
        {
            AVCodec* codec = ffmpeg.avcodec_find_encoder_by_name(name);
            if (codec == null) continue;
            AVCodecContext* c = ffmpeg.avcodec_alloc_context3(codec);
            c->width = _outW; c->height = _outH;
            c->time_base = new AVRational { num = 1, den = fps };
            c->framerate = new AVRational { num = fps, den = 1 };
            c->pix_fmt = fmt; c->gop_size = fps; c->max_b_frames = 0; c->bit_rate = bitRate;
            // Ask the encoder to emit SPS/PPS as out-of-band extradata. We then use
            // the dump_extra bitstream filter to re-insert that extradata in-band on
            // every keyframe (see InitDumpExtraBsf). This is uniform across encoders
            // and works around h264_amf, which otherwise never emits SPS/PPS in-band
            // and leaves the Android decoder with no way to configure itself (black
            // screen; audio, decoded separately, is unaffected).
            c->flags |= ffmpeg.AV_CODEC_FLAG_GLOBAL_HEADER;
            switch (name)
            {
                case "h264_amf":
                    ffmpeg.av_opt_set(c->priv_data, "usage", "ultralowlatency", 0);
                    ffmpeg.av_opt_set(c->priv_data, "quality", "speed", 0);
                    ffmpeg.av_opt_set(c->priv_data, "rc", "cbr", 0);
                    break;
                case "h264_qsv":
                    ffmpeg.av_opt_set(c->priv_data, "preset", "veryfast", 0);
                    ffmpeg.av_opt_set_int(c->priv_data, "async_depth", 1, 0);
                    break;
                case "libx264":
                    ffmpeg.av_opt_set(c->priv_data, "preset", "ultrafast", 0);
                    ffmpeg.av_opt_set(c->priv_data, "tune", "zerolatency", 0);
                    break;
            }
            if (ffmpeg.avcodec_open2(c, codec, null) == 0) { _c = c; EncoderName = name; return fmt; }
            ffmpeg.avcodec_free_context(&c);
        }
        throw new ApplicationException("No usable H.264 encoder (amf/qsv/libx264) could be opened.");
    }

    /// <summary>
    /// Set up the "dump_extra" bitstream filter on the encoder's output. It copies
    /// the codec extradata (SPS/PPS) to the front of each keyframe packet — except
    /// when a packet already begins with that extradata, so it is a no-op for
    /// encoders that already include headers in-band. This guarantees the Android
    /// MediaCodec receiver always sees SPS (NAL type 7) + PPS (type 8) with every
    /// keyframe and can configure its decoder, fixing the AMD (h264_amf) black
    /// screen without affecting Intel QSV or software libx264 output.
    /// </summary>
    private void InitDumpExtraBsf()
    {
        AVBitStreamFilter* filter = ffmpeg.av_bsf_get_by_name("dump_extra");
        if (filter == null) throw new ApplicationException("dump_extra bitstream filter not found.");
        fixed (AVBSFContext** pbsf = &_bsf)
        {
            if (ffmpeg.av_bsf_alloc(filter, pbsf) < 0)
                throw new ApplicationException("av_bsf_alloc(dump_extra) failed.");
        }
        if (ffmpeg.avcodec_parameters_from_context(_bsf->par_in, _c) < 0)
            throw new ApplicationException("avcodec_parameters_from_context failed.");
        _bsf->time_base_in = _c->time_base;
        // Default freq is "keyframe" (k) — insert on keyframes only, which is what we want.
        if (ffmpeg.av_bsf_init(_bsf) < 0)
            throw new ApplicationException("av_bsf_init(dump_extra) failed.");
    }

    /// <summary>Encode one BGRA frame (pointer valid for the duration of the call).</summary>
    public void Encode(IntPtr bgra, int stride, long pts, Action<byte[]> sink)
    {
        ffmpeg.av_frame_make_writable(_frame);
        var srcData = new byte_ptrArray4();
        srcData[0] = (byte*)bgra;
        var srcLine = new int_array4();
        srcLine[0] = stride;
        ffmpeg.sws_scale(_sws, srcData, srcLine, 0, _srcH, _frame->data, _frame->linesize);

        _frame->pts = pts;
        int ret = ffmpeg.avcodec_send_frame(_c, _frame);
        if (ret < 0) throw new ApplicationException("send_frame failed: " + FfErr.Str(ret));
        while (ret >= 0)
        {
            ret = ffmpeg.avcodec_receive_packet(_c, _pkt);
            if (ret == ffmpeg.AVERROR(ffmpeg.EAGAIN) || ret == ffmpeg.AVERROR_EOF) break;
            if (ret < 0) throw new ApplicationException("receive_packet failed: " + FfErr.Str(ret));

            // Run the packet through dump_extra so keyframes carry SPS/PPS in-band.
            int bret = ffmpeg.av_bsf_send_packet(_bsf, _pkt);
            ffmpeg.av_packet_unref(_pkt);
            if (bret < 0) throw new ApplicationException("bsf_send_packet failed: " + FfErr.Str(bret));
            while (true)
            {
                bret = ffmpeg.av_bsf_receive_packet(_bsf, _pkt);
                if (bret == ffmpeg.AVERROR(ffmpeg.EAGAIN) || bret == ffmpeg.AVERROR_EOF) break;
                if (bret < 0) throw new ApplicationException("bsf_receive_packet failed: " + FfErr.Str(bret));
                var buf = new byte[_pkt->size];
                System.Runtime.InteropServices.Marshal.Copy((IntPtr)_pkt->data, buf, 0, _pkt->size);
                ffmpeg.av_packet_unref(_pkt);
                sink(buf);
            }
        }
    }

    public void Dispose()
    {
        if (_sws != null) ffmpeg.sws_freeContext(_sws);
        fixed (AVBSFContext** bp = &_bsf) ffmpeg.av_bsf_free(bp);
        fixed (AVPacket** p = &_pkt) ffmpeg.av_packet_free(p);
        fixed (AVFrame** f = &_frame) ffmpeg.av_frame_free(f);
        fixed (AVCodecContext** c = &_c) ffmpeg.avcodec_free_context(c);
    }
}

internal static unsafe class FfErr
{
    public static string Str(int code)
    {
        const int size = 1024;
        byte* buf = stackalloc byte[size];
        ffmpeg.av_strerror(code, buf, (ulong)size);
        return System.Runtime.InteropServices.Marshal.PtrToStringAnsi((IntPtr)buf) ?? code.ToString();
    }
}
