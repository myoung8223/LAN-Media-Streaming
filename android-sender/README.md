# LAN Media Sender — Android

Mirror an Android device's screen and audio to a **LAN Media** receiver (Android,
Linux, or any panel running the receiver), over your local network only. It
speaks the same v3 protocol as the Windows sender — UDP discovery, TLS with
trust-on-first-use pinning, and the muxed H.264 + Opus stream — so your existing
receivers work with it unchanged.

Because it runs on Android, it also runs on **Chromebooks that support Android
apps**, which is the main reason to have it: cast a tablet or a Chromebook to a
wall panel with no capture card and no PC.

## What it captures

* **Screen:** the whole device display, via MediaProjection, encoded to H.264
  with the device's hardware encoder (MediaCodec). SPS/PPS are prepended to every
  keyframe, matching what the receivers expect.
* **Audio:** the device's *playback* audio (what it's playing), via
  AudioPlaybackCapture, encoded to Opus. This needs Android 10+ and an Opus
  encoder on the device; if either is missing, or an app opts out of capture, the
  sender automatically streams **video only**.

## Build

Open the `android-sender/` folder in **Android Studio** (Giraffe or newer) and
let it sync Gradle, then Run, or build an APK from the command line:

```bash
cd android-sender
./gradlew assembleDebug     # or assembleRelease
```

The APK lands in `app/build/outputs/apk/`. Requirements: `minSdk 29`
(Android 10), `compileSdk/targetSdk 34`. This is the canonical build — it always
streams full video + system audio, and installs only on Android 10+ (so no device
silently drops to video-only). Non-EOL Chromebooks that support Android apps run
Android 10+ and are covered by this build.

> A separate `minSdk 28` variant exists for **EOL Chromebooks frozen on Android 9**
> (e.g. Braswell boards like the Dell 3180 "kefka"): same code, but it installs on
> Android 9 and streams video only there, since system-audio capture requires
> API 29. Use that build only for such legacy devices.

## Install & test

### Android tablet/phone (easiest)

Sideload the APK: copy it to the device and open it (enable "install unknown
apps" for your file manager when prompted), or `adb install app-debug.apk`.

### Chromebook that supports Android apps

- **Personal / unmanaged:** enable the Linux (Crostini) environment and ADB
  debugging (Settings → turn on Linux, then Developer options → enable ADB), then
  `adb install app-debug.apk` into the Android container. There's no tap-to-install
  by default on Chrome OS.
- **Managed (school):** users can't sideload. Publish the app as a **private app**
  in the Google Play Console (private to your organization) and push it through
  the **Google Admin console** (Apps → Android). That's the supported fleet path.

### Using it

1. Enter the receiver's **name** (UDP discovery) or its **IP/hostname**, the
   **port**, and the **password** if the receiver has one. Set **Encrypt (TLS)**
   to match the receiver.
2. Set the max **resolution**, **bitrate**, and **FPS** (defaults 1920×1080,
   10 Mbps, 30 fps). The device screen is scaled to fit inside that box, aspect
   preserved, never upscaled.
3. Tick **Include device audio** if you want sound.
4. Tap **Start streaming**. Android will ask for:
   - notification permission (Android 13+),
   - microphone permission (this is how Android gates *playback* capture — it's
     not recording your mic unless you're on the mic-only variant), and
   - the **"Start recording / casting?"** screen-capture consent. This consent is
     per-session by design — you'll confirm it each time you start.
5. On the first encrypted connection the receiver's certificate fingerprint is
   pinned; it's shown at the bottom of the screen so you can verify it matches the
   receiver, and **Clear pinned certificate** resets it.

The stream keeps running in the background (a foreground-service notification with
a **Stop** action stays in the shade). Tap **Stop streaming** in the app or the
notification to end it.

## Notes & limitations

- **Screen-capture consent is per session.** Android does not allow persisting it;
  every Start shows the system dialog. That's a platform rule, not a bug.
- **System audio caveats.** Some apps (and DRM-protected audio) opt out of
  playback capture; those won't be included. Video is unaffected. If the device
  has no Opus encoder, the sender falls back to video-only automatically.
- **Chrome OS capture.** On a Chromebook, what MediaProjection captures (the whole
  Chrome OS desktop vs. just the Android surface) varies by device and Chrome OS
  version — worth testing on your actual hardware before relying on it.
- **Latency.** Hardware H.264 encode is fast; end-to-end latency is dominated by
  the receiver's playout buffer, which you tune on the receiver as before.

## Project layout

```
app/src/main/java/com/lanmedia/sender/
  MainActivity.kt     UI, permissions, MediaProjection consent, settings
  SenderService.kt    foreground service: capture + connect + mux
  VideoEncoder.kt     MediaCodec H.264 from a VirtualDisplay surface
  AudioEncoder.kt     AudioPlaybackCapture → MediaCodec Opus (graceful fallback)
  Protocol.kt         wire constants, sha256, line reader
  Discovery.kt        UDP name-discovery client
  TlsUtil.kt          TLS client with TOFU certificate pinning
```
