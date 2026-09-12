/*
 * common.h — shared definitions for the LAN Media Receiver (Linux).
 *
 * Two binaries link against config.c and this header:
 *   - lanmedia-receiver          (the streaming daemon; receiver.c)
 *   - lanmedia-receiver-settings (the GTK3 config editor; settings_gui.c)
 *
 * The wire protocol matches the Windows sender / Android receiver "v3" mux:
 *   handshake: one line of JSON, then a "{\"ok\":true}\n" reply
 *   media:     repeating [type:1][ptsMs:8 BE][len:4 BE][payload]
 *              type 0 = Opus audio, type 1 = H.264 (Annex-B) video
 */
#ifndef LANMEDIA_COMMON_H
#define LANMEDIA_COMMON_H

#include <stddef.h>

/* ---- app identity ---- */
#define LM_VERSION          "1.1.0"
#define LM_REPO_URL         "https://github.com/myoung8223/LAN-Media-Streaming"

/* ---- protocol constants (must match Protocol.cs / Protocol.kt) ---- */
#define LM_MAGIC            "LANMED01"
#define LM_DISCOVERY_MAGIC  "LANDISC1"
#define LM_DEFAULT_PORT     45788
#define LM_DISCOVERY_PORT   45789
#define LM_STREAM_AUDIO     0
#define LM_STREAM_VIDEO     1

/* ---- playout buffer bounds (mirrors the Android receiver) ---- */
#define LM_BUFFER_MIN_MS    20
#define LM_BUFFER_MAX_MS    500
#define LM_BUFFER_DEFAULT   150

/* ---- persisted configuration ---- */
typedef struct {
    char name[64];       /* discovery name, e.g. "Rcvr-482" */
    int  port;           /* TCP media port (also answered over UDP discovery) */
    int  buffer_ms;      /* requested playout buffer; clamped, fps floor applied at stream start */
    char password[128];  /* optional; empty = no auth required */
    int  use_tls;        /* 1 = TLS with self-signed cert + TOFU pinning */
    int  fullscreen;     /* 1 = fullscreen video window, 0 = windowed */
    char decoder[16];    /* "auto" | "hardware" | "software" (VAAPI + fallback) */
} Config;

/* Fill *cfg with defaults (also used when no config file exists yet). */
void config_defaults(Config *cfg);

/* Absolute path helpers (return a pointer to a static buffer; not reentrant). */
const char *config_dir(void);       /* ~/.config/lanmedia            */
const char *config_file_path(void); /* ~/.config/lanmedia/receiver.conf */
const char *cert_file_path(void);   /* ~/.config/lanmedia/cert.pem   */
const char *key_file_path(void);    /* ~/.config/lanmedia/key.pem    */

/* Load config from disk into *cfg (defaults for any missing keys).
 * Returns 1 if a file was read, 0 if defaults were used. */
int config_load(Config *cfg);

/* Persist *cfg to disk (creates the config dir if needed).
 * Returns 0 on success, -1 on failure. */
int config_save(const Config *cfg);

/* Clamp/normalise fields to safe ranges (called by load and save). */
void config_sanitize(Config *cfg);

/* Create the config directory (~/.config/lanmedia) if it doesn't exist. */
void config_ensure_dir(void);

#endif /* LANMEDIA_COMMON_H */
