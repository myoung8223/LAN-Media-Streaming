# LAN Media Receiver — Linux

A lightweight C receiver for the **LAN Media Streaming** project: it listens for
the Windows sender, verifies the TLS certificate and password, and shows the
muxed H.264 + Opus stream fullscreen with audio — over your local network only.

It speaks the same "v3" protocol as the Android receiver: UDP name discovery,
a JSON handshake, optional TLS with trust-on-first-use certificate pinning, and
an fps-aware playout buffer (20 ms floor at 60 fps, 40 ms at 30 fps).

Two small binaries:

- **`lanmedia-receiver`** — the streaming daemon (SDL2 + FFmpeg's libavcodec +
  libopus + OpenSSL). No GUI toolkit linked, so the always-running part stays lean.
- **`lanmedia-receiver-settings`** — a small GTK3 window to edit the config and
  show the certificate fingerprint.

Software H.264 decode for now (rock-solid, trivial for 1080p on any x86-64). The
decode path is isolated so VAAPI hardware decode can be added later without
touching the network or display code.

---

## 1. Install build dependencies (Lubuntu / Ubuntu 24.04)

```bash
sudo apt update
sudo apt install -y build-essential pkg-config \
    libsdl2-dev libavcodec-dev libavutil-dev libswscale-dev \
    libopus-dev libssl-dev libgtk-3-dev openssl
```

That's everything needed to build and run. (`openssl` — the command-line tool —
is used once, on first run, to generate the self-signed certificate.)

## 2. Build

```bash
make
```

This produces `lanmedia-receiver` and `lanmedia-receiver-settings` in the current
directory. To install them system-wide (into `/usr/local/bin`) plus the settings
menu entry:

```bash
sudo make install
```

## 3. Configure

Run the settings GUI:

```bash
lanmedia-receiver-settings      # or ./lanmedia-receiver-settings if not installed
```

Set the **receiver name** (what you'll type on the sender), **port** (default
45788), **playout buffer**, optional **password**, and whether to require **TLS**
(leave on to match the sender's default). Click **Save**. The window also shows
the **certificate fingerprint** — you'll confirm this once on the sender the
first time it connects.

There's also an **H.264 decode** mode — `auto` (default), `hardware`, or
`software` — see "Hardware decode" below.

Prefer a file? Config lives at `~/.config/lanmedia/receiver.conf`; see
`config.example.conf` for the format. While testing, set `fullscreen=0` for a
windowed view that's easier to exit.

You can print the fingerprint any time with:

```bash
lanmedia-receiver --fingerprint
```

## 4. First test (foreground)

Just run it in a terminal inside your desktop session:

```bash
lanmedia-receiver
```

It logs `listening on TCP 45788 as "…"`. On the Windows sender, enter this
receiver's name (or its IP), match the password and the Encrypt/TLS setting, and
click **Start streaming**. The video window pops up fullscreen; **press Esc or Q**
to close it and return to listening. `Ctrl-C` in the terminal stops the daemon.

If TLS is on, the sender pins the certificate on first connect — verify the
fingerprint it shows matches the one from the settings window.

## 5. Hardware (GPU) decode — optional but recommended on weak CPUs

The receiver auto-detects VAAPI GPU decode and **falls back to software
automatically** if it isn't available, so it works out of the box either way.
On low-power machines (old Chromebooks, Atom-class CPUs) GPU decode can be the
difference between smooth and stuttery 1080p, and it lowers CPU/heat everywhere.

Install the VA driver for your GPU and confirm H.264 is exposed:

```bash
sudo apt install -y vainfo mesa-va-drivers intel-media-va-driver
# Older Intel iGPUs (Braswell / Skylake-era, e.g. many repurposed Chromebooks
# and the ThinkPad E460) may also need the legacy driver:
sudo apt install -y i965-va-driver
vainfo        # look for VAProfileH264Main / VAProfileH264High with VAEntrypointVLD
```

If `vainfo` lists H.264 with `VAEntrypointVLD`, that box can hardware-decode our
stream. Pick the mode in the settings GUI ("H.264 decode": auto / hardware /
software) or the `decoder=` config key; `auto` uses the GPU when present. On
startup the daemon logs which path it chose — `video decoder: VAAPI (hardware)`
or `video decoder: software` — so you can confirm at a glance. If hardware decode
ever errors mid-stream, it drops to software on the fly rather than showing black.

## 6. Run it automatically (wall-panel / kiosk)

The daemon needs a graphical session (X display + audio), so it runs *inside*
your desktop login, not as a detached system service. Two options:

### Option A — XDG autostart (simplest for an auto-login panel)

```bash
mkdir -p ~/.config/autostart
cp autostart/lanmedia-receiver.desktop ~/.config/autostart/
```

It then starts whenever the desktop session logs in. Combined with Lubuntu's
auto-login (Preferences → nothing needed if set during install, or edit
`/etc/lightdm/lightdm.conf`), the panel comes up listening after every boot with
no interaction.

### Option B — systemd user service

```bash
mkdir -p ~/.config/systemd/user
cp systemd/lanmedia-receiver.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now lanmedia-receiver.service
sudo loginctl enable-linger "$USER"     # start at boot without an interactive login
```

If the service can't open the display, make sure systemd's user manager has the
session environment (run once from your session, or add to autostart):

```bash
systemctl --user import-environment DISPLAY XAUTHORITY
```

Check status / logs:

```bash
systemctl --user status lanmedia-receiver
journalctl --user -u lanmedia-receiver -f
```

## 7. Firewall (only if you enabled ufw)

A minimal Lubuntu install has no firewall by default. If you turned on `ufw`:

```bash
sudo ufw allow 45788/tcp   # media stream
sudo ufw allow 45789/udp   # name discovery
```

## Controls

- **Esc** or **Q** — close the video window (returns to listening).
- Closing the window does not stop the daemon; it waits for the next stream.

## Notes & limitations

- **Decode:** VAAPI GPU decode (Intel/AMD) with automatic fallback to software
  H.264 — auto-detected at startup and again at runtime if the GPU errors. See
  the "Hardware decode" section. NVIDIA (VDPAU/NVDEC) isn't wired up; those boxes
  use the software path.
- **Audio-only mode** (a sender with video unchecked) is not implemented yet on
  Linux — the receiver expects the v3 video stream (audio is included in that
  stream via the sender's "Include audio"). It rejects an audio-only sender with
  a clear message.
- **Resolution** is chosen by the sender; the receiver scales to the display,
  preserving aspect ratio (letterboxed if needed).
- **Wayland:** built and tested for X11 (Lubuntu's default). SDL2 also supports
  Wayland, but the kiosk/auto-show behavior is validated on X11.

## Uninstall

```bash
sudo rm -f /usr/local/bin/lanmedia-receiver /usr/local/bin/lanmedia-receiver-settings
sudo rm -f /usr/local/share/applications/lanmedia-receiver-settings.desktop
rm -f ~/.config/autostart/lanmedia-receiver.desktop
systemctl --user disable --now lanmedia-receiver.service 2>/dev/null
rm -f ~/.config/systemd/user/lanmedia-receiver.service
rm -rf ~/.config/lanmedia          # config + certificate
```

## Project layout

```
src/common.h        shared config + protocol constants
src/config.c        config load/save (linked into both binaries)
src/receiver.c      the streaming daemon
src/settings_gui.c  the GTK3 settings editor
Makefile            gcc build (pkg-config)
systemd/            user service unit
autostart/          XDG autostart entry (kiosk)
desktop/            settings menu entry
config.example.conf annotated example config
```
