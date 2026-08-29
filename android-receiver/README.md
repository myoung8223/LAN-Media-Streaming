LAN Media Receiver (Android)
A native Android app that receives an audio stream over your LAN and plays it,
running as a foreground service so playback keeps going when the app is
minimized or the panel is showing something else. Built for an Android
interactive display (e.g. Newline Q Pro) receiving audio from a Windows PC.
No internet, no third-party services — a direct TCP connection on your LAN.
Optional password — only senders with the matching password can connect.
FOSS / auditable — no WebRTC, no external SDKs; ~4 small Kotlin files plus
the standard AndroidX UI libraries. Wire format is documented in
`PROTOCOL.md`.
The matching Windows sender (C#/.NET) is the next piece; until it exists you
can verify this app with the included Python test sender (see Testing below).
---
Build the APK (Android Studio, on your Windows PC)
Install Android Studio (latest stable) from developer.android.com. During
first launch let it install the Android SDK it recommends.
Open this folder (`lan-audio-receiver`) in Android Studio: File → Open →
select the folder. Let Gradle sync finish — on first sync it downloads the
Android Gradle Plugin, the SDK platform (API 34), and build-tools. This needs
internet on the build PC (one time).
Build the installable APK: Build → Build Bundle(s) / APK(s) → Build APK(s).
When it finishes, click locate — the file is:
```
   app/build/outputs/apk/debug/app-debug.apk
   ```
(A debug-signed APK is fine for sideloading.)
Command-line alternative (no Android Studio UI): with the Android SDK
installed and `ANDROID_HOME`/`local.properties` set, run `./gradlew assembleDebug`.
---
Install on the panel (Newline Q Pro / any Android)
On the panel, allow installing from unknown sources (Settings → Security, or
the per-app "Install unknown apps" permission for your file manager).
Copy `app-debug.apk` to the panel (USB drive, network share, or
`adb install app-debug.apk`) and open it to install.
Launch LAN Media Receiver. It shows the panel's IP address and a
port (default `45788`). Optionally set a password. It starts
listening automatically on launch (toggle: Start listening when the app
opens); you can also tap Start / Stop listening manually. The port and
password fields lock while listening — press Stop to change them.
Minimize sends the app to the background while it keeps playing.
Start automatically after reboot (toggle) resumes listening after the panel
restarts — see the note below.
Important for always-on use: tap Open app settings and exempt the app
from battery optimization ("Don't optimize" / "Unrestricted"). This is what
lets Android keep the foreground service — and the audio — alive indefinitely.
> **Encryption:** with **Encrypt connection (TLS)** on (default), the panel runs
> a TLS server using a self-signed certificate it generates once (private key
> stays on the device) and shows a **security fingerprint**. The first time you
> connect the sender, compare that fingerprint to the one shown in the PC app.
> The toggle must match on both ends.
> **Reboot auto-start caveat:** newer Android versions and some OEM display
> firmware restrict apps (and foreground services) from starting themselves at
> boot. The toggle does the standard thing, but you may also need to allow this
> app in the panel's **Auto-start** list (in its system settings), and it's worth
> verifying on the actual device that listening resumes after a reboot.
---
Testing (before the Windows app exists)
On the Windows PC, with Python 3 installed, use the included sender to prove
the receiver works and — critically — that audio keeps playing when the app is
backgrounded:
```bash
# 440 Hz test tone (no file needed):
python tools/test_sender.py <panel-ip>

# with a password set on the receiver:
python tools/test_sender.py <panel-ip> --password room123

# stream a real WAV file (any rate/width; converted automatically):
python tools/test_sender.py <panel-ip> --wav some_music.wav
```
Then on the panel: press Home / switch to another app while the tone plays. If
the sound continues, the core requirement is met. (Wrong password? The sender
prints "rejected by receiver".)
---
How it works
A foreground service (`ReceiverService`) opens a `ServerSocket`, accepts one
sender at a time, checks the password, then streams the incoming PCM into an
`AudioTrack`. A partial wake-lock + Wi-Fi lock keep the CPU and radio awake.
The tiny handshake + raw-PCM format is in `PROTOCOL.md`.
`minSdk 26` (Android 8.0), `targetSdk 34`.
Roadmap
Windows sender app (C#/.NET + NAudio, WASAPI loopback) — next.
TLS on the socket (v2) for on-wire encryption.
Opus encoding to cut bandwidth (optional; PCM is fine on a LAN).
Optional mDNS auto-discovery so the sender finds the panel without typing an IP.
Authors
Designed and coded by Claude Opus 4.8 to the direction of Mike Young
(architect / product lead). FOSS — license it as you wish; MIT is a good default.
