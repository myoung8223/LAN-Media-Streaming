# LAN Media Sender (Windows)

A native Windows app that captures the PC's audio and streams it to the
**LAN Media Receiver** Android app over your local network — no browser, no
internet, no third-party services. Pairs with the receiver over protocol v1
(see the receiver's `PROTOCOL.md`).

- Captures **system audio** (WASAPI loopback — "what's playing") or the **microphone**.
- Connects directly to the panel's **IP:port**, with an optional **password**.
- **Auto-reconnects** if the link drops.
- Built on **.NET 8 + NAudio**. FOSS / auditable.

---

## Build the app (on your Windows PC)

1. Install the free **.NET 8 SDK** from https://dotnet.microsoft.com/download
   (no Visual Studio required).
2. In this folder, produce a self-contained single `.exe` (no runtime needed on
   the target PC):

   ```powershell
   dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
   ```

   The result is:
   ```
   bin\Release\net8.0-windows\win-x64\publish\LAN Media Sender.exe
   ```

   *Smaller build* (if the PC already has the .NET 8 Desktop Runtime): drop
   `--self-contained true` and add `--self-contained false`.

   *Just run it while developing:* `dotnet run`

---

## Use it

1. On the Android panel, open **LAN Media Receiver**, note its **IP** and
   **port**, set a **password** if you want, and tap **Start listening**.
2. Launch **LAN Media Sender** on the PC. Enter the panel's **IP**, the same
   **port** and **password**, choose **System audio** or **Microphone**, and
   click **Start streaming**.
3. The level bar shows audio is flowing and the status reads "● Streaming".
   Settings are remembered for next time.

> **System audio** streams whatever is playing on the PC (music, video, etc.).
> **Microphone** streams the default mic. If you hear nothing, check the panel's
> volume and that Windows is actually playing sound on the default output device.

### Encryption (TLS)

With **Encrypt connection (TLS)** on (default, must match the panel), the audio
is encrypted end-to-end. The panel has a self-signed certificate; the sender
trusts it on first connect and **pins** its fingerprint, shown near the bottom of
the window. Compare that fingerprint once against the one on the panel to be sure
you're talking to the right device. If the panel is ever reinstalled/reset, its
certificate changes — press **Clear pinned certificate** and reconnect to pin the
new one.

---

## How it works

- `AudioStreamer` opens a WASAPI capture (`WasapiLoopbackCapture` for system
  audio, `WasapiCapture` for the mic), converts each buffer to interleaved
  **stereo signed-16 PCM** at the capture sample rate, and streams it over a
  single TCP connection after a one-line JSON handshake (magic + SHA-256 of the
  password + format). A bounded queue drops the oldest audio if the network
  stalls, so latency can't build up.
- The password is sent only as a SHA-256 hash. v1 is plaintext PCM on the LAN;
  TLS is the planned v2 upgrade (matching the receiver).

## Files

| File | Purpose |
|------|---------|
| `Protocol.cs`      | Constants + SHA-256 helper (matches the receiver). |
| `AudioStreamer.cs` | Capture → convert → connect/handshake/stream + reconnect. |
| `MainForm.cs`      | The window (IP, port, password, source, level, status). |
| `Settings.cs`      | Remembers settings in `%AppData%\LanMediaSender`. |
| `Program.cs`       | Entry point. |

## Roadmap

- **TLS** encryption on the socket (v2).
- **Opus** encoding to cut bandwidth (optional; PCM is fine on a LAN).
- **mDNS auto-discovery** so you don't type the IP.

## Authors

Designed and coded by **Claude Opus 4.8** to the direction of **Mike Young**
(architect / product lead). FOSS — license as you wish; MIT is a good default.
