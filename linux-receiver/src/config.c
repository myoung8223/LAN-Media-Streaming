/*
 * config.c — load/save a simple key=value config file, shared by the daemon
 * and the settings GUI. Format (one per line):
 *
 *     name=Rcvr-482
 *     port=45788
 *     buffer_ms=150
 *     password=
 *     use_tls=1
 *     fullscreen=1
 *
 * Stored at ~/.config/lanmedia/receiver.conf.
 */
#include "common.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <pwd.h>

/* Resolve the user's home dir: $HOME, falling back to the passwd entry. */
static const char *home_dir(void)
{
    const char *h = getenv("HOME");
    if (h && *h) return h;
    struct passwd *pw = getpwuid(getuid());
    return (pw && pw->pw_dir) ? pw->pw_dir : "/tmp";
}

const char *config_dir(void)
{
    static char buf[512];
    snprintf(buf, sizeof buf, "%s/.config/lanmedia", home_dir());
    return buf;
}

/* Uses a small ring of static buffers so several path calls can be live at
 * once — e.g. cert_file_path() and key_file_path() in a single printf. */
static const char *path_in_cfg(const char *leaf)
{
    static char bufs[4][600];
    static int idx = 0;
    char *buf = bufs[idx];
    idx = (idx + 1) & 3;
    snprintf(buf, 600, "%s/%s", config_dir(), leaf);
    return buf;
}

const char *config_file_path(void) { return path_in_cfg("receiver.conf"); }
const char *cert_file_path(void)   { return path_in_cfg("cert.pem"); }
const char *key_file_path(void)    { return path_in_cfg("key.pem"); }

void config_defaults(Config *cfg)
{
    memset(cfg, 0, sizeof *cfg);
    /* A distinct-ish default name; the user can rename in the GUI. */
    snprintf(cfg->name, sizeof cfg->name, "Rcvr-%03d", (int)(getpid() % 900) + 100);
    cfg->port = LM_DEFAULT_PORT;
    cfg->buffer_ms = LM_BUFFER_DEFAULT;
    cfg->password[0] = '\0';
    cfg->use_tls = 1;
    cfg->fullscreen = 1;
    snprintf(cfg->decoder, sizeof cfg->decoder, "auto");
}

void config_sanitize(Config *cfg)
{
    if (cfg->port < 1024 || cfg->port > 65535) cfg->port = LM_DEFAULT_PORT;
    if (cfg->buffer_ms < LM_BUFFER_MIN_MS) cfg->buffer_ms = LM_BUFFER_MIN_MS;
    if (cfg->buffer_ms > LM_BUFFER_MAX_MS) cfg->buffer_ms = LM_BUFFER_MAX_MS;
    cfg->use_tls = cfg->use_tls ? 1 : 0;
    cfg->fullscreen = cfg->fullscreen ? 1 : 0;
    /* Ensure NUL-termination even if a hand-edited file overran a field. */
    cfg->name[sizeof cfg->name - 1] = '\0';
    cfg->password[sizeof cfg->password - 1] = '\0';
    cfg->decoder[sizeof cfg->decoder - 1] = '\0';
    if (cfg->name[0] == '\0')
        snprintf(cfg->name, sizeof cfg->name, "Rcvr-%03d", (int)(getpid() % 900) + 100);
    if (strcmp(cfg->decoder, "auto") != 0 &&
        strcmp(cfg->decoder, "hardware") != 0 &&
        strcmp(cfg->decoder, "software") != 0)
        snprintf(cfg->decoder, sizeof cfg->decoder, "auto");
}

/* Strip trailing CR/LF/space in place. */
static void rstrip(char *s)
{
    size_t n = strlen(s);
    while (n > 0 && (s[n-1] == '\n' || s[n-1] == '\r' || s[n-1] == ' ' || s[n-1] == '\t'))
        s[--n] = '\0';
}

int config_load(Config *cfg)
{
    config_defaults(cfg);

    FILE *f = fopen(config_file_path(), "r");
    if (!f) { config_sanitize(cfg); return 0; }

    char line[512];
    while (fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        char *key = line;
        char *val = eq + 1;
        rstrip(key);
        rstrip(val);

        if      (strcmp(key, "name") == 0)       snprintf(cfg->name, sizeof cfg->name, "%s", val);
        else if (strcmp(key, "port") == 0)       cfg->port = atoi(val);
        else if (strcmp(key, "buffer_ms") == 0)  cfg->buffer_ms = atoi(val);
        else if (strcmp(key, "password") == 0)   snprintf(cfg->password, sizeof cfg->password, "%s", val);
        else if (strcmp(key, "use_tls") == 0)    cfg->use_tls = atoi(val);
        else if (strcmp(key, "fullscreen") == 0) cfg->fullscreen = atoi(val);
        else if (strcmp(key, "decoder") == 0)    snprintf(cfg->decoder, sizeof cfg->decoder, "%s", val);
    }
    fclose(f);
    config_sanitize(cfg);
    return 1;
}

/* mkdir -p for the config directory (two levels: ~/.config then /lanmedia). */
void config_ensure_dir(void)
{
    char path[512];
    const char *h = home_dir();
    snprintf(path, sizeof path, "%s/.config", h);
    mkdir(path, 0700);
    snprintf(path, sizeof path, "%s/.config/lanmedia", h);
    mkdir(path, 0700);
}

int config_save(const Config *cfg)
{
    Config tmp = *cfg;
    config_sanitize(&tmp);
    config_ensure_dir();

    FILE *f = fopen(config_file_path(), "w");
    if (!f) return -1;
    fprintf(f, "# LAN Media Receiver configuration\n");
    fprintf(f, "name=%s\n", tmp.name);
    fprintf(f, "port=%d\n", tmp.port);
    fprintf(f, "buffer_ms=%d\n", tmp.buffer_ms);
    fprintf(f, "password=%s\n", tmp.password);
    fprintf(f, "use_tls=%d\n", tmp.use_tls);
    fprintf(f, "fullscreen=%d\n", tmp.fullscreen);
    fprintf(f, "decoder=%s\n", tmp.decoder);
    fclose(f);
    /* Keep the file private — it can contain a password. */
    chmod(config_file_path(), 0600);
    return 0;
}
