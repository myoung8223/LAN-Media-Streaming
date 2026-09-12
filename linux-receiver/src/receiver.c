/*
 * receiver.c — LAN Media Receiver daemon (Linux).
 *
 * Listens for the Windows sender, verifies the TLS certificate / password,
 * decodes the muxed H.264 + Opus "v3" stream, and shows it fullscreen (SDL2)
 * with audio, using an fps-aware playout buffer that matches the Android app.
 *
 * Dependencies: SDL2, libavcodec/libavutil (+ libswscale), libopus, OpenSSL.
 * Software H.264 decode for now; the decode path is isolated so a VAAPI
 * hwaccel can be added later without touching the network or display code.
 *
 * Build: see Makefile (pkg-config sdl2 libavcodec libavutil libswscale opus openssl).
 */
#define _GNU_SOURCE
#include "common.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>      /* strcasecmp */
#include <stdint.h>
#include <stdarg.h>       /* va_list for log_msg */
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include <sys/socket.h>
#include <sys/select.h>
#include <sys/stat.h>     /* chmod */
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <openssl/pem.h>

#include <SDL2/SDL.h>

#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
#include <libavutil/imgutils.h>
#include <libavutil/hwcontext.h>
#include <libswscale/swscale.h>

#include <opus/opus.h>

/* ------------------------------------------------------------------ */
/* Globals & small helpers                                            */
/* ------------------------------------------------------------------ */

static volatile sig_atomic_t g_running = 1;
static Config g_cfg;

static void on_signal(int sig) { (void)sig; g_running = 0; }

static long long now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void log_msg(const char *fmt, ...)
{
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[lanmedia] ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

/* Lowercase hex SHA-256 of a string (matches Protocol.Sha256Hex on the sender). */
static void sha256_hex(const char *s, char out[65])
{
    unsigned char h[SHA256_DIGEST_LENGTH];
    SHA256((const unsigned char *)s, strlen(s), h);
    static const char *hex = "0123456789abcdef";
    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++) {
        out[i*2]   = hex[h[i] >> 4];
        out[i*2+1] = hex[h[i] & 0xf];
    }
    out[64] = '\0';
}

/* ------------------------------------------------------------------ */
/* Minimal JSON field extraction (flat object, known keys)            */
/* ------------------------------------------------------------------ */

/* Copy the string value of "key" into out. Returns 1 if found. */
static int json_str(const char *json, const char *key, char *out, size_t outsz)
{
    char pat[64];
    snprintf(pat, sizeof pat, "\"%s\"", key);
    const char *p = strstr(json, pat);
    if (!p) return 0;
    p = strchr(p + strlen(pat), ':');
    if (!p) return 0;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return 0;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i + 1 < outsz) {
        if (*p == '\\' && p[1]) p++;   /* naive unescape: keep next char literally */
        out[i++] = *p++;
    }
    out[i] = '\0';
    return 1;
}

/* Integer value of "key", or def if absent. */
static long json_int(const char *json, const char *key, long def)
{
    char pat[64];
    snprintf(pat, sizeof pat, "\"%s\"", key);
    const char *p = strstr(json, pat);
    if (!p) return def;
    p = strchr(p + strlen(pat), ':');
    if (!p) return def;
    return strtol(p + 1, NULL, 10);
}

/* Boolean value of "key" (true/false), or def if absent. */
static int json_bool(const char *json, const char *key, int def)
{
    char pat[64];
    snprintf(pat, sizeof pat, "\"%s\"", key);
    const char *p = strstr(json, pat);
    if (!p) return def;
    p = strchr(p + strlen(pat), ':');
    if (!p) return def;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    return strncmp(p, "true", 4) == 0;
}

/* ------------------------------------------------------------------ */
/* Connection abstraction: plain socket or TLS                        */
/* ------------------------------------------------------------------ */

typedef struct { int fd; SSL *ssl; } Conn;

static int conn_read_some(Conn *c, void *buf, int n)
{
    if (c->ssl) return SSL_read(c->ssl, buf, n);
    return (int)recv(c->fd, buf, n, 0);
}

static int conn_write(Conn *c, const void *buf, int n)
{
    if (c->ssl) return SSL_write(c->ssl, buf, n);
    return (int)send(c->fd, buf, n, MSG_NOSIGNAL);
}

/* Read exactly n bytes. Returns 1 on success, 0 on EOF/timeout/error. */
static int conn_read_full(Conn *c, void *buf, int n)
{
    unsigned char *p = (unsigned char *)buf;
    int got = 0;
    while (got < n) {
        int r = conn_read_some(c, p + got, n - got);
        if (r <= 0) return 0;
        got += r;
    }
    return 1;
}

/* Read one '\n'-terminated line (handshake). Returns length, or -1. */
static int conn_read_line(Conn *c, char *buf, int max)
{
    int i = 0;
    while (i < max - 1) {
        char ch;
        int r = conn_read_some(c, &ch, 1);
        if (r <= 0) return -1;
        if (ch == '\n') break;
        if (ch != '\r') buf[i++] = ch;
    }
    buf[i] = '\0';
    return i;
}

/* ------------------------------------------------------------------ */
/* TLS setup: self-signed cert (generated on first run) + fingerprint */
/* ------------------------------------------------------------------ */

static int file_exists(const char *path)
{
    FILE *f = fopen(path, "r");
    if (f) { fclose(f); return 1; }
    return 0;
}

/* Generate a self-signed cert+key via the openssl CLI if they don't exist.
 * (Simple and robust; the daemon otherwise only uses the OpenSSL library.) */
static int ensure_cert(void)
{
    if (file_exists(cert_file_path()) && file_exists(key_file_path())) return 0;

    config_ensure_dir();   /* openssl can't write the cert if the dir is missing */
    char cmd[1400];
    snprintf(cmd, sizeof cmd,
        "openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes "
        "-subj '/CN=lanmedia' -keyout '%s' -out '%s' >/dev/null 2>&1",
        key_file_path(), cert_file_path());
    int rc = system(cmd);
    if (rc != 0 || !file_exists(cert_file_path()) || !file_exists(key_file_path())) {
        log_msg("failed to generate TLS certificate (is the 'openssl' command installed?)");
        return -1;
    }
    chmod(key_file_path(), 0600);
    log_msg("generated a new self-signed certificate");
    return 0;
}

/* Uppercase colon-separated SHA-256 of the cert (matches the Android display). */
static int cert_fingerprint(char out[128])
{
    FILE *f = fopen(cert_file_path(), "r");
    if (!f) return -1;
    X509 *x = PEM_read_X509(f, NULL, NULL, NULL);
    fclose(f);
    if (!x) return -1;

    unsigned char der[4096];
    unsigned char *p = der;
    int len = i2d_X509(x, &p);
    X509_free(x);
    if (len <= 0) return -1;

    unsigned char h[SHA256_DIGEST_LENGTH];
    SHA256(der, len, h);
    char *o = out;
    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++) {
        o += sprintf(o, "%02X", h[i]);
        if (i != SHA256_DIGEST_LENGTH - 1) *o++ = ':';
    }
    *o = '\0';
    return 0;
}

static SSL_CTX *make_ssl_ctx(void)
{
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (!ctx) return NULL;
    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    if (SSL_CTX_use_certificate_file(ctx, cert_file_path(), SSL_FILETYPE_PEM) <= 0 ||
        SSL_CTX_use_PrivateKey_file(ctx, key_file_path(), SSL_FILETYPE_PEM) <= 0) {
        log_msg("failed to load TLS certificate/key");
        SSL_CTX_free(ctx);
        return NULL;
    }
    return ctx;
}

/* ------------------------------------------------------------------ */
/* UDP discovery responder (runs on its own thread)                   */
/* ------------------------------------------------------------------ */

static void *discovery_thread(void *arg)
{
    (void)arg;
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return NULL;
    int yes = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(LM_DISCOVERY_PORT);
    if (bind(s, (struct sockaddr *)&addr, sizeof addr) < 0) {
        log_msg("discovery: cannot bind UDP %d (%s)", LM_DISCOVERY_PORT, strerror(errno));
        close(s);
        return NULL;
    }

    /* Non-blocking so we can notice shutdown. */
    struct timeval tv = { 1, 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    char buf[2048];
    while (g_running) {
        struct sockaddr_in from;
        socklen_t fl = sizeof from;
        int n = recvfrom(s, buf, sizeof buf - 1, 0, (struct sockaddr *)&from, &fl);
        if (n <= 0) continue;
        buf[n] = '\0';

        char magic[32] = "";
        json_str(buf, "magic", magic, sizeof magic);
        if (strcmp(magic, LM_DISCOVERY_MAGIC) != 0) continue;

        /* Reply only to a matching name or a wildcard query. */
        char q[64] = "";
        json_str(buf, "q", q, sizeof q);
        if (q[0] && strcasecmp(q, g_cfg.name) != 0) continue;

        char reply[256];
        int rn = snprintf(reply, sizeof reply,
            "{\"magic\":\"%s\",\"name\":\"%s\",\"port\":%d,\"tls\":%s}\n",
            LM_DISCOVERY_MAGIC, g_cfg.name, g_cfg.port, g_cfg.use_tls ? "true" : "false");
        sendto(s, reply, rn, 0, (struct sockaddr *)&from, fl);
    }
    close(s);
    return NULL;
}

/* ------------------------------------------------------------------ */
/* SDL video + audio                                                  */
/* ------------------------------------------------------------------ */

typedef struct {
    SDL_Window   *win;
    SDL_Renderer *ren;
    SDL_Texture  *tex;
    int    tex_w, tex_h;
    Uint32 tex_fmt;                 /* SDL pixel format of the current texture */
    struct SwsContext *sws;         /* lazily created if a frame needs conversion */
    AVFrame *conv;                  /* YUV420P scratch for the sws path */
} Video;

static int video_open(Video *v, int fullscreen)
{
    memset(v, 0, sizeof *v);
    Uint32 flags = SDL_WINDOW_ALLOW_HIGHDPI;
    if (fullscreen) flags |= SDL_WINDOW_FULLSCREEN_DESKTOP;
    v->win = SDL_CreateWindow("LAN Media Receiver",
        SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        1280, 720, flags);
    if (!v->win) { log_msg("SDL_CreateWindow: %s", SDL_GetError()); return -1; }
    SDL_ShowCursor(SDL_DISABLE);
    v->ren = SDL_CreateRenderer(v->win, -1,
        SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!v->ren) v->ren = SDL_CreateRenderer(v->win, -1, 0); /* fall back to software */
    if (!v->ren) { log_msg("SDL_CreateRenderer: %s", SDL_GetError()); return -1; }
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "1"); /* linear scaling */
    return 0;
}

static void video_close(Video *v)
{
    if (v->tex) SDL_DestroyTexture(v->tex);
    if (v->ren) SDL_DestroyRenderer(v->ren);
    if (v->win) SDL_DestroyWindow(v->win);
    if (v->sws) sws_freeContext(v->sws);
    if (v->conv) av_frame_free(&v->conv);
    memset(v, 0, sizeof *v);
}

/* Create/recreate the streaming texture only when size or format changes. */
static int video_ensure_texture(Video *v, Uint32 fmt, int w, int h)
{
    if (v->tex && v->tex_w == w && v->tex_h == h && v->tex_fmt == fmt) return 0;
    if (v->tex) SDL_DestroyTexture(v->tex);
    v->tex = SDL_CreateTexture(v->ren, fmt, SDL_TEXTUREACCESS_STREAMING, w, h);
    v->tex_w = w; v->tex_h = h; v->tex_fmt = fmt;
    if (!v->tex) { log_msg("SDL_CreateTexture: %s", SDL_GetError()); return -1; }
    return 0;
}

/* Draw the current texture into the window, scaled to fit (aspect preserved). */
static void video_blit(Video *v, int w, int h)
{
    int ow, oh;
    SDL_GetRendererOutputSize(v->ren, &ow, &oh);
    double scale = (double)ow / w;
    if ((double)oh / h < scale) scale = (double)oh / h;
    SDL_Rect dst;
    dst.w = (int)(w * scale);
    dst.h = (int)(h * scale);
    dst.x = (ow - dst.w) / 2;
    dst.y = (oh - dst.h) / 2;
    SDL_SetRenderDrawColor(v->ren, 0, 0, 0, 255);
    SDL_RenderClear(v->ren);
    SDL_RenderCopy(v->ren, v->tex, NULL, &dst);
    SDL_RenderPresent(v->ren);
}

/* Present a decoded frame. Handles the common software format (YUV420P) and
 * VAAPI's transfer output (NV12) natively; anything else is converted to
 * YUV420P via swscale. */
static void video_present(Video *v, AVFrame *f)
{
    int w = f->width, h = f->height;

    if (f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P) {
        if (video_ensure_texture(v, SDL_PIXELFORMAT_IYUV, w, h) != 0) return;
        SDL_UpdateYUVTexture(v->tex, NULL,
                             f->data[0], f->linesize[0],
                             f->data[1], f->linesize[1],
                             f->data[2], f->linesize[2]);
    } else if (f->format == AV_PIX_FMT_NV12) {
        if (video_ensure_texture(v, SDL_PIXELFORMAT_NV12, w, h) != 0) return;
        SDL_UpdateNVTexture(v->tex, NULL,
                            f->data[0], f->linesize[0],
                            f->data[1], f->linesize[1]);
    } else {
        /* Convert whatever we got (e.g. YUV422/444) to YUV420P for SDL. */
        if (!v->conv || v->conv->width != w || v->conv->height != h) {
            if (v->conv) av_frame_free(&v->conv);
            v->conv = av_frame_alloc();
            v->conv->format = AV_PIX_FMT_YUV420P;
            v->conv->width = w; v->conv->height = h;
            av_frame_get_buffer(v->conv, 32);
        }
        v->sws = sws_getCachedContext(v->sws, w, h, f->format,
                                      w, h, AV_PIX_FMT_YUV420P,
                                      SWS_BILINEAR, NULL, NULL, NULL);
        sws_scale(v->sws, (const uint8_t *const *)f->data, f->linesize,
                  0, h, v->conv->data, v->conv->linesize);
        if (video_ensure_texture(v, SDL_PIXELFORMAT_IYUV, w, h) != 0) return;
        SDL_UpdateYUVTexture(v->tex, NULL,
                             v->conv->data[0], v->conv->linesize[0],
                             v->conv->data[1], v->conv->linesize[1],
                             v->conv->data[2], v->conv->linesize[2]);
    }
    video_blit(v, w, h);
}

/* ------------------------------------------------------------------ */
/* Playout buffer floor (matches VideoStream.floorMsForFps on Android) */
/* ------------------------------------------------------------------ */

static long floor_ms_for_fps(int fps)
{
    if (fps < 1 || fps > 240) fps = 30;
    long f = (long)(1.2 * 1000.0 / fps + 0.5);
    return f < LM_BUFFER_MIN_MS ? LM_BUFFER_MIN_MS : f;
}

/* ------------------------------------------------------------------ */
/* Video session: decode + display the muxed stream                   */
/* ------------------------------------------------------------------ */

/* Pump SDL events; return 1 if the user asked to close (ESC/quit). */
static int pump_events(void)
{
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
        if (e.type == SDL_QUIT) return 1;
        if (e.type == SDL_KEYDOWN &&
            (e.key.keysym.sym == SDLK_ESCAPE || e.key.keysym.sym == SDLK_q))
            return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* H.264 decoder open: VAAPI hardware when available, software otherwise */
/* ------------------------------------------------------------------ */

/* get_format callback: pick VAAPI if the decoder offers it, else fail (which
 * makes avcodec fall back — we detect that and reopen a software decoder). */
static enum AVPixelFormat get_hw_format(AVCodecContext *ctx,
                                        const enum AVPixelFormat *fmts)
{
    (void)ctx;
    for (const enum AVPixelFormat *p = fmts; *p != AV_PIX_FMT_NONE; p++)
        if (*p == AV_PIX_FMT_VAAPI) return *p;
    return AV_PIX_FMT_NONE;
}

/* Does this decoder advertise a VAAPI hwaccel config? */
static int codec_supports_vaapi(const AVCodec *codec)
{
    for (int i = 0;; i++) {
        const AVCodecHWConfig *cfg = avcodec_get_hw_config(codec, i);
        if (!cfg) return 0;
        if ((cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) &&
            cfg->device_type == AV_HWDEVICE_TYPE_VAAPI)
            return 1;
    }
}

/* Try to create a VAAPI device context (NULL device = system default, i.e.
 * /dev/dri/renderD128 or the X display). Returns NULL if unavailable. */
static AVBufferRef *try_create_vaapi(void)
{
    AVBufferRef *ref = NULL;
    if (av_hwdevice_ctx_create(&ref, AV_HWDEVICE_TYPE_VAAPI, NULL, NULL, 0) < 0)
        return NULL;
    return ref;
}

/* Open an H.264 decoder context. If hw_dev is non-NULL, configure VAAPI.
 * Returns the context, or NULL on failure. */
static AVCodecContext *open_h264(const AVCodec *codec, AVBufferRef *hw_dev)
{
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return NULL;
    if (hw_dev) {
        ctx->hw_device_ctx = av_buffer_ref(hw_dev);
        ctx->get_format = get_hw_format;
    }
    if (avcodec_open2(ctx, codec, NULL) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    return ctx;
}

static void run_video_session(Conn *c, const char *hs, SDL_AudioDeviceID audio_dev)
{
    int width  = (int)json_int(hs, "width", 1920);
    int height = (int)json_int(hs, "height", 1080);
    int fps    = (int)json_int(hs, "fps", 30);
    int has_audio = json_bool(hs, "audio", 0);

    long delay_ms = g_cfg.buffer_ms;
    long floor_ms = floor_ms_for_fps(fps);
    if (delay_ms < floor_ms) delay_ms = floor_ms;
    log_msg("stream: %dx%d @ %dfps%s · buffer %ldms",
            width, height, fps, has_audio ? " + audio" : "", delay_ms);

    /* --- video decoder: try VAAPI (unless disabled), fall back to software --- */
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) { log_msg("no H.264 decoder in libavcodec"); return; }

    AVBufferRef *hw_dev = NULL;
    int want_hw = (strcmp(g_cfg.decoder, "software") != 0) && codec_supports_vaapi(codec);
    if (want_hw) {
        hw_dev = try_create_vaapi();
        if (!hw_dev) { want_hw = 0; log_msg("VAAPI unavailable — using software decode"); }
    } else if (strcmp(g_cfg.decoder, "software") != 0) {
        log_msg("no VAAPI H.264 support in libavcodec — using software decode");
    }

    AVCodecContext *vc = open_h264(codec, want_hw ? hw_dev : NULL);
    if (!vc && want_hw) {                    /* hardware open failed → software */
        log_msg("hardware decoder open failed — using software decode");
        want_hw = 0;
        vc = open_h264(codec, NULL);
    }
    if (!vc) {
        log_msg("cannot open H.264 decoder");
        if (hw_dev) av_buffer_unref(&hw_dev);
        return;
    }
    int is_hw = want_hw;
    log_msg("video decoder: %s", is_hw ? "VAAPI (hardware)" : "software");

    AVPacket *pkt = av_packet_alloc();
    AVFrame  *frame = av_frame_alloc();
    AVFrame  *sw_frame = av_frame_alloc();   /* CPU target for VAAPI hwframe transfer */

    /* --- audio decoder --- */
    OpusDecoder *od = NULL;
    if (has_audio) {
        int oe = 0;
        od = opus_decoder_create(48000, 2, &oe);
        if (oe != OPUS_OK) { od = NULL; has_audio = 0; log_msg("opus init failed"); }
    }

    /* --- display window --- */
    Video vid;
    if (video_open(&vid, g_cfg.fullscreen) != 0) goto cleanup;

    /* --- audio prime: delay_ms of silence so audio trails video by the buffer --- */
    if (has_audio && audio_dev) {
        SDL_ClearQueuedAudio(audio_dev);
        int silence_bytes = 48000 * 2 * 2 * (int)delay_ms / 1000; /* 48k stereo S16 */
        void *silence = calloc(1, silence_bytes);
        if (silence) { SDL_QueueAudio(audio_dev, silence, silence_bytes); free(silence); }
        SDL_PauseAudioDevice(audio_dev, 0);
    }

    long long base_ns = 0;
    long long base_pts = 0;
    int16_t pcm[5760 * 2];           /* up to 120ms @ 48k stereo */
    unsigned char header[13];
    unsigned char *payload = NULL;
    int payload_cap = 0;
    int stop = 0;

    while (g_running && !stop) {
        if (!conn_read_full(c, header, 13)) break;
        int type = header[0];
        long long pts = 0;
        for (int k = 1; k <= 8; k++) pts = (pts << 8) | header[k];
        int len = (header[9] << 24) | (header[10] << 16) | (header[11] << 8) | header[12];
        if (len <= 0 || len > 20000000) { log_msg("bad frame length %d", len); break; }

        if (len > payload_cap) {
            unsigned char *np = realloc(payload, len);
            if (!np) break;
            payload = np; payload_cap = len;
        }
        if (!conn_read_full(c, payload, len)) break;

        if (base_ns == 0) { base_pts = pts; base_ns = now_ns(); }

        if (type == LM_STREAM_VIDEO) {
            av_new_packet(pkt, len);
            memcpy(pkt->data, payload, len);
            pkt->pts = pts;
            int derr = avcodec_send_packet(vc, pkt);
            av_packet_unref(pkt);
            while (derr == 0) {
                int r = avcodec_receive_frame(vc, frame);
                if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) break;
                if (r < 0) {
                    /* Decode error: if we were on hardware, drop to software and
                     * resync on the next keyframe (arrives within ~1s). */
                    if (is_hw) {
                        log_msg("hardware decode error — falling back to software");
                        avcodec_free_context(&vc);
                        av_buffer_unref(&hw_dev);
                        vc = open_h264(codec, NULL);
                        is_hw = 0;
                        if (!vc) stop = 1;
                    }
                    break;
                }

                AVFrame *disp = frame;
                if (frame->format == AV_PIX_FMT_VAAPI) {
                    /* Copy the GPU surface down to CPU memory (usually NV12). */
                    if (av_hwframe_transfer_data(sw_frame, frame, 0) < 0) {
                        log_msg("hwframe transfer failed — falling back to software");
                        av_frame_unref(frame);
                        avcodec_free_context(&vc);
                        av_buffer_unref(&hw_dev);
                        vc = open_h264(codec, NULL);
                        is_hw = 0;
                        if (!vc) stop = 1;
                        break;
                    }
                    disp = sw_frame;
                }

                /* Schedule presentation on the shared timeline + buffer. */
                long long target = base_ns + (pts - base_pts + delay_ms) * 1000000LL;
                long long wait = target - now_ns();
                if (wait > 0 && wait < 1000000000LL) {
                    struct timespec ts = { wait / 1000000000LL, wait % 1000000000LL };
                    nanosleep(&ts, NULL);
                }
                video_present(&vid, disp);
                av_frame_unref(frame);
                if (disp == sw_frame) av_frame_unref(sw_frame);
                if (pump_events()) { stop = 1; break; }
            }
        } else if (type == LM_STREAM_AUDIO && od && audio_dev) {
            int samples = opus_decode(od, payload, len, pcm, 5760, 0);
            if (samples > 0)
                SDL_QueueAudio(audio_dev, pcm, samples * 2 * (int)sizeof(int16_t));
        }

        if (pump_events()) break;
    }

    if (audio_dev) { SDL_PauseAudioDevice(audio_dev, 1); SDL_ClearQueuedAudio(audio_dev); }
    video_close(&vid);
    free(payload);

cleanup:
    if (od) opus_decoder_destroy(od);
    if (frame) av_frame_free(&frame);
    if (sw_frame) av_frame_free(&sw_frame);
    if (pkt) av_packet_free(&pkt);
    if (vc) avcodec_free_context(&vc);
    if (hw_dev) av_buffer_unref(&hw_dev);
}

/* ------------------------------------------------------------------ */
/* One accepted client: TLS, handshake, auth, dispatch                */
/* ------------------------------------------------------------------ */

static void handle_client(int fd, SSL_CTX *ssl_ctx, SDL_AudioDeviceID audio_dev)
{
    Conn c = { fd, NULL };
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    /* Media reads shouldn't hang forever if a sender vanishes mid-stream. */
    struct timeval tv = { 8, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    if (ssl_ctx) {
        c.ssl = SSL_new(ssl_ctx);
        SSL_set_fd(c.ssl, fd);
        if (SSL_accept(c.ssl) <= 0) {
            log_msg("TLS handshake failed");
            SSL_free(c.ssl);
            close(fd);
            return;
        }
    }

    char line[8192];
    if (conn_read_line(&c, line, sizeof line) < 0) goto done;

    char magic[32] = "";
    json_str(line, "magic", magic, sizeof magic);
    if (strcmp(magic, LM_MAGIC) != 0) { conn_write(&c, "{\"ok\":false,\"error\":\"bad magic\"}\n", 33); goto done; }

    /* Auth: sender sends sha256hex(password); empty local password = open. */
    if (g_cfg.password[0]) {
        char expect[65]; sha256_hex(g_cfg.password, expect);
        char got[128] = ""; json_str(line, "auth", got, sizeof got);
        if (strcasecmp(got, expect) != 0) {
            conn_write(&c, "{\"ok\":false,\"error\":\"bad password\"}\n", 36);
            log_msg("rejected a sender (wrong/no password)");
            goto done;
        }
    }

    if (json_bool(line, "video", 0)) {
        conn_write(&c, "{\"ok\":true}\n", 12);
        run_video_session(&c, line, audio_dev);
    } else {
        /* Audio-only (legacy v2) mode is not implemented yet on Linux. */
        conn_write(&c, "{\"ok\":false,\"error\":\"audio-only not supported\"}\n", 48);
        log_msg("rejected an audio-only sender (video mode required)");
    }

done:
    if (c.ssl) { SSL_shutdown(c.ssl); SSL_free(c.ssl); }
    close(fd);
}

/* ------------------------------------------------------------------ */
/* main                                                               */
/* ------------------------------------------------------------------ */

static int print_fingerprint_and_exit(void)
{
    if (ensure_cert() != 0) return 1;
    char fp[128];
    if (cert_fingerprint(fp) == 0) { printf("%s\n", fp); return 0; }
    fprintf(stderr, "could not read certificate fingerprint\n");
    return 1;
}

int main(int argc, char **argv)
{
    config_load(&g_cfg);

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--fingerprint") == 0)
            return print_fingerprint_and_exit();
        if (strcmp(argv[i], "--help") == 0) {
            printf("Usage: lanmedia-receiver [--fingerprint]\n"
                   "  Reads config from %s\n"
                   "  --fingerprint  print the TLS certificate fingerprint and exit\n",
                   config_file_path());
            return 0;
        }
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    /* Let our signal handlers run instead of SDL's. */
    SDL_SetHint(SDL_HINT_NO_SIGNAL_HANDLERS, "1");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0) {
        log_msg("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    /* One audio device kept open for the process lifetime. */
    SDL_AudioSpec want, have;
    memset(&want, 0, sizeof want);
    want.freq = 48000;
    want.format = AUDIO_S16SYS;
    want.channels = 2;
    want.samples = 1024;
    want.callback = NULL;            /* push model via SDL_QueueAudio */
    SDL_AudioDeviceID audio_dev = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    if (!audio_dev) log_msg("audio unavailable: %s (video will still play)", SDL_GetError());

    SSL_CTX *ssl_ctx = NULL;
    if (g_cfg.use_tls) {
        SSL_library_init();
        SSL_load_error_strings();
        if (ensure_cert() != 0) { SDL_Quit(); return 1; }
        ssl_ctx = make_ssl_ctx();
        if (!ssl_ctx) { SDL_Quit(); return 1; }
        char fp[128];
        if (cert_fingerprint(fp) == 0) log_msg("certificate fingerprint: %s", fp);
    }

    /* Listen socket. */
    int ls = socket(AF_INET, SOCK_STREAM, 0);
    int yes = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(g_cfg.port);
    if (bind(ls, (struct sockaddr *)&addr, sizeof addr) < 0) {
        log_msg("cannot bind TCP %d: %s", g_cfg.port, strerror(errno));
        close(ls); if (ssl_ctx) SSL_CTX_free(ssl_ctx); SDL_Quit();
        return 1;
    }
    listen(ls, 4);

    pthread_t disc;
    pthread_create(&disc, NULL, discovery_thread, NULL);

    log_msg("listening on TCP %d as \"%s\"%s", g_cfg.port, g_cfg.name,
            g_cfg.use_tls ? " (TLS)" : "");

    while (g_running) {
        fd_set rfds; FD_ZERO(&rfds); FD_SET(ls, &rfds);
        struct timeval tv = { 1, 0 };
        int r = select(ls + 1, &rfds, NULL, NULL, &tv);
        if (r <= 0) continue;              /* timeout → re-check g_running */
        int fd = accept(ls, NULL, NULL);
        if (fd < 0) continue;
        handle_client(fd, ssl_ctx, audio_dev);
        log_msg("listening on TCP %d as \"%s\"%s", g_cfg.port, g_cfg.name,
                g_cfg.use_tls ? " (TLS)" : "");
    }

    log_msg("shutting down");
    close(ls);
    pthread_join(disc, NULL);
    if (audio_dev) SDL_CloseAudioDevice(audio_dev);
    if (ssl_ctx) SSL_CTX_free(ssl_ctx);
    SDL_Quit();
    return 0;
}
