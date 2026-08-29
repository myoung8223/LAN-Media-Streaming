# LAN Media wire protocol (v2)

Deliberately tiny, text-inspectable, no third-party services. One TCP
connection, **sender → receiver**. v2 adds optional Opus compression; PCM mode is
unchanged from v1 and both ends still interoperate in PCM.

## Sequence

1. **Sender connects** to the receiver's `IP:port` (default port `45788`).

2. **Sender → Receiver:** one line of UTF-8 JSON, terminated by `\n`:

   ```json
   {"magic":"LANMED01","version":2,"auth":"<sha256hex|empty>","sampleRate":48000,"channels":2,"bits":16,"codec":"opus","frameSize":960}
   ```

   - `magic` must equal `LANMED01`.
   - `auth` is the lowercase-hex **SHA-256 of the shared password**, or `""` if
     no password is configured on the receiver.
   - `codec` is `"pcm"` (default if absent) or `"opus"`.
   - `sampleRate` (Hz), `channels` (1 or 2), `bits` (16). For `opus`, these are
     always `48000` / `2` / `16`, and `frameSize` is the per-channel samples per
     Opus frame (`960` = 20 ms).

3. **Receiver → Sender:** one line of UTF-8 JSON, terminated by `\n`:

   ```json
   {"ok":true}
   ```
   or, on rejection (then the socket closes):
   ```json
   {"ok":false,"error":"bad password"}
   ```

4. **Sender streams audio** in the negotiated codec until the socket closes:

   - **`pcm`** — raw interleaved **little-endian signed 16-bit PCM**, no framing.
     Stereo is `L R L R …`. The format from step 2 holds for the whole
     connection.
   - **`opus`** — a sequence of length-prefixed Opus packets. Each packet is:

     ```
     [2-byte big-endian unsigned length N][N bytes: one Opus packet]
     ```

     Each packet is one 20 ms frame of 48 kHz stereo audio. The receiver decodes
     with the platform Opus decoder and plays the resulting PCM.

5. **Disconnect:** either side closes the socket. The receiver returns to
   waiting for the next connection.

## Transport security (TLS)

When encryption is enabled (default), the entire exchange above runs **inside a
TLS session**, so the handshake and the audio are encrypted on the wire:

- The **receiver is the TLS server**. It generates a **self-signed certificate**
  once from an in-memory RSA key (a software key held in the app's private
  storage — no external CA, nothing leaves the device), presents it through a
  standard PKCS12 keystore, and displays the certificate's **SHA-256
  fingerprint**.
- The **sender is the TLS client**. It **pins the certificate fingerprint**
  (trust-on-first-use, then required to match) — so it only ever trusts that one
  panel. Verify the fingerprint shown in the sender against the one on the panel
  the first time.
- Modern TLS (1.2 / 1.3) is negotiated.

Both ends have an on/off toggle (must match). With TLS off, the same handshake +
audio run in the clear (LAN-trusted fallback). Either way the **password** is
never sent in clear text (only its SHA-256), and it authorizes *who* may send —
separate from TLS, which encrypts the channel.

> Note: with TLS on, the sender never emits an empty record — empty capture
> buffers are dropped and Opus DTX is disabled — so a run of empty records can't
> trip the receiver's TLS library during silence.

## Name discovery (UDP)

So the sender can find a panel by a human name instead of a hard-coded IP
(which DHCP may change), each panel answers a tiny UDP query. This is separate
from and optional to the audio connection above — if discovery gets no answer,
the sender falls back to a manually entered IP.

- Fixed UDP port **45789** (independent of the audio port).
- **Sender → broadcast `255.255.255.255:45789`:** one line of JSON:

  ```json
  {"magic":"LANDISC1","q":"Room 214"}
  ```

  `q` is the panel name being looked for; empty `q` is a wildcard (any panel may
  answer).

- **Panel → unicast reply** (only if `q` is empty or matches its name,
  case-insensitive):

  ```json
  {"magic":"LANDISC1","name":"Room 214","port":45788,"tls":true}
  ```

  The panel's **IP is the reply's source address** (not embedded). `port` is the
  panel's current audio port, so changing the audio port needs no change on the
  sender.

The sender re-resolves on every connect (including auto-reconnect), so a panel
that gets a new DHCP lease is simply found again at its new address. Discovery is
unauthenticated — it only reveals a name and port — and cannot hijack a session:
the audio connection is still gated by the password and the pinned TLS
certificate. The panel needs a Wi-Fi multicast lock to receive the broadcast,
which it holds while listening.

## Defaults

| Field       | Default |
|-------------|---------|
| Port        | 45788   |
| Codec       | opus (128 kbps) — PCM selectable |
| Sample rate | 48000   |
| Channels    | 2       |
| Bits        | 16 (PCM S16LE) |
| Opus frame  | 960 samples / 20 ms |

Bandwidth: raw PCM ≈ 48000 × 2 × 2 bytes = **~1.5 Mbit/s**. Opus at 128 kbps is
roughly **~12× smaller**, which helps on congested Wi-Fi; selectable 64–192 kbps.
