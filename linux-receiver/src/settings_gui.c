/*
 * settings_gui.c — small GTK3 configuration editor for the LAN Media Receiver.
 *
 * Edits ~/.config/lanmedia/receiver.conf (via config.c), shows the TLS
 * certificate fingerprint (to verify pinning on the sender), and can restart
 * the systemd --user service so changes take effect immediately.
 *
 * This is a separate binary from the daemon on purpose: the always-running
 * service never links GTK, keeping its footprint small.
 *
 * Build: pkg-config gtk+-3.0 (see Makefile).
 */
#include "common.h"

#include <gtk/gtk.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    GtkWidget *name;
    GtkWidget *port;        /* spin */
    GtkWidget *buffer;      /* spin */
    GtkWidget *password;
    GtkWidget *tls;         /* check */
    GtkWidget *fullscreen;  /* check */
    GtkWidget *decoder;     /* combo: auto/hardware/software */
    GtkWidget *fp_value;    /* label */
    GtkWidget *status;      /* label */
} Ui;

/* Ask the daemon binary for the certificate fingerprint (it generates the
 * cert on first run). Falls back to a friendly message if unavailable. */
static void refresh_fingerprint(Ui *ui)
{
    FILE *p = popen("lanmedia-receiver --fingerprint 2>/dev/null", "r");
    char line[160] = "";
    if (p) {
        if (fgets(line, sizeof line, p)) {
            size_t n = strlen(line);
            while (n && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = '\0';
        }
        pclose(p);
    }
    if (line[0])
        gtk_label_set_text(GTK_LABEL(ui->fp_value), line);
    else
        gtk_label_set_text(GTK_LABEL(ui->fp_value),
                           "(unavailable — enable TLS and start the service once)");
}

static void ui_to_config(Ui *ui, Config *cfg)
{
    config_defaults(cfg);
    snprintf(cfg->name, sizeof cfg->name, "%s", gtk_entry_get_text(GTK_ENTRY(ui->name)));
    cfg->port = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(ui->port));
    cfg->buffer_ms = gtk_spin_button_get_value_as_int(GTK_SPIN_BUTTON(ui->buffer));
    snprintf(cfg->password, sizeof cfg->password, "%s", gtk_entry_get_text(GTK_ENTRY(ui->password)));
    cfg->use_tls = gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(ui->tls)) ? 1 : 0;
    cfg->fullscreen = gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(ui->fullscreen)) ? 1 : 0;
    gchar *dec = gtk_combo_box_text_get_active_text(GTK_COMBO_BOX_TEXT(ui->decoder));
    if (dec) { snprintf(cfg->decoder, sizeof cfg->decoder, "%s", dec); g_free(dec); }
}

static void on_save(GtkButton *b, gpointer data)
{
    (void)b;
    Ui *ui = data;
    Config cfg;
    ui_to_config(ui, &cfg);
    if (config_save(&cfg) == 0)
        gtk_label_set_text(GTK_LABEL(ui->status), "Saved.");
    else
        gtk_label_set_text(GTK_LABEL(ui->status), "Could not write config file.");
}

static void on_save_restart(GtkButton *b, gpointer data)
{
    Ui *ui = data;
    on_save(b, data);
    int rc = system("systemctl --user restart lanmedia-receiver.service");
    if (rc == 0)
        gtk_label_set_text(GTK_LABEL(ui->status), "Saved and service restarted.");
    else
        gtk_label_set_text(GTK_LABEL(ui->status),
                           "Saved. Could not restart service (is it installed?).");
    refresh_fingerprint(ui);
}

static void on_close(GtkButton *b, gpointer data)
{
    (void)b; (void)data;
    gtk_main_quit();
}

/* Convenience: a right-aligned caption in the grid. */
static GtkWidget *caption(const char *text)
{
    GtkWidget *l = gtk_label_new(text);
    gtk_widget_set_halign(l, GTK_ALIGN_END);
    return l;
}

int main(int argc, char **argv)
{
    gtk_init(&argc, &argv);

    Config cfg;
    config_load(&cfg);

    GtkWidget *win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(win), "LAN Media Receiver — Settings");
    gtk_window_set_resizable(GTK_WINDOW(win), FALSE);
    gtk_container_set_border_width(GTK_CONTAINER(win), 16);
    g_signal_connect(win, "destroy", G_CALLBACK(gtk_main_quit), NULL);

    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_container_add(GTK_CONTAINER(win), vbox);

    GtkWidget *title = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(title),
        "<span size='x-large' weight='bold'>LAN Media Receiver</span>");
    gtk_box_pack_start(GTK_BOX(vbox), title, FALSE, FALSE, 0);

    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 8);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 10);
    gtk_box_pack_start(GTK_BOX(vbox), grid, FALSE, FALSE, 0);

    Ui ui;

    ui.name = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(ui.name), cfg.name);
    gtk_entry_set_width_chars(GTK_ENTRY(ui.name), 22);
    gtk_grid_attach(GTK_GRID(grid), caption("Receiver name"), 0, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), ui.name, 1, 0, 1, 1);

    ui.port = gtk_spin_button_new_with_range(1024, 65535, 1);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(ui.port), cfg.port);
    gtk_grid_attach(GTK_GRID(grid), caption("Port"), 0, 1, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), ui.port, 1, 1, 1, 1);

    ui.buffer = gtk_spin_button_new_with_range(LM_BUFFER_MIN_MS, LM_BUFFER_MAX_MS, 5);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(ui.buffer), cfg.buffer_ms);
    gtk_grid_attach(GTK_GRID(grid), caption("Playout buffer (ms)"), 0, 2, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), ui.buffer, 1, 2, 1, 1);

    ui.password = gtk_entry_new();
    gtk_entry_set_visibility(GTK_ENTRY(ui.password), FALSE);
    gtk_entry_set_text(GTK_ENTRY(ui.password), cfg.password);
    gtk_grid_attach(GTK_GRID(grid), caption("Password (optional)"), 0, 3, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), ui.password, 1, 3, 1, 1);

    ui.tls = gtk_check_button_new_with_label("Encrypt connection (TLS)");
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(ui.tls), cfg.use_tls);
    gtk_grid_attach(GTK_GRID(grid), ui.tls, 1, 4, 1, 1);

    ui.fullscreen = gtk_check_button_new_with_label("Fullscreen video");
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(ui.fullscreen), cfg.fullscreen);
    gtk_grid_attach(GTK_GRID(grid), ui.fullscreen, 1, 5, 1, 1);

    ui.decoder = gtk_combo_box_text_new();
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(ui.decoder), "auto");
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(ui.decoder), "hardware");
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(ui.decoder), "software");
    {
        int di = 0;
        if (strcmp(cfg.decoder, "hardware") == 0) di = 1;
        else if (strcmp(cfg.decoder, "software") == 0) di = 2;
        gtk_combo_box_set_active(GTK_COMBO_BOX(ui.decoder), di);
    }
    gtk_grid_attach(GTK_GRID(grid), caption("H.264 decode"), 0, 6, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), ui.decoder, 1, 6, 1, 1);

    /* Fingerprint block. */
    GtkWidget *fp_cap = gtk_label_new("Security fingerprint (verify once on the sender):");
    gtk_widget_set_halign(fp_cap, GTK_ALIGN_START);
    gtk_box_pack_start(GTK_BOX(vbox), fp_cap, FALSE, FALSE, 0);

    ui.fp_value = gtk_label_new("…");
    gtk_label_set_selectable(GTK_LABEL(ui.fp_value), TRUE);
    gtk_label_set_line_wrap(GTK_LABEL(ui.fp_value), TRUE);
    gtk_widget_set_halign(ui.fp_value, GTK_ALIGN_START);
    gtk_box_pack_start(GTK_BOX(vbox), ui.fp_value, FALSE, FALSE, 0);

    /* Buttons. */
    GtkWidget *bbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_widget_set_halign(bbox, GTK_ALIGN_END);
    GtkWidget *b_save = gtk_button_new_with_label("Save");
    GtkWidget *b_apply = gtk_button_new_with_label("Save & restart service");
    GtkWidget *b_close = gtk_button_new_with_label("Close");
    gtk_box_pack_start(GTK_BOX(bbox), b_save, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(bbox), b_apply, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(bbox), b_close, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), bbox, FALSE, FALSE, 0);

    ui.status = gtk_label_new("");
    gtk_widget_set_halign(ui.status, GTK_ALIGN_START);
    gtk_box_pack_start(GTK_BOX(vbox), ui.status, FALSE, FALSE, 0);

    g_signal_connect(b_save, "clicked", G_CALLBACK(on_save), &ui);
    g_signal_connect(b_apply, "clicked", G_CALLBACK(on_save_restart), &ui);
    g_signal_connect(b_close, "clicked", G_CALLBACK(on_close), &ui);

    refresh_fingerprint(&ui);

    gtk_widget_show_all(win);
    gtk_main();
    return 0;
}
