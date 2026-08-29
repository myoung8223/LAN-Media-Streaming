#!/usr/bin/env python3
"""
LAN Media — test sender (protocol v1).

Streams audio to the Android receiver so you can verify it plays and keeps
playing when the app is backgrounded — BEFORE the real Windows app exists.

Usage:
    python test_sender.py <receiver-ip> [--port 45788] [--password PW]
                          [--wav FILE] [--seconds N] [--tone HZ]

Examples:
    # 440 Hz test tone (no file needed), stereo 48 kHz, until Ctrl-C:
    python test_sender.py 192.168.1.50

    # stream a WAV file (any sample rate/width; it is converted to S16LE):
    python test_sender.py 192.168.1.50 --password room123 --wav song.wav

Requires only the Python standard library. (WAV conversion uses `audioop`,
which ships with CPython 3.x.)
"""
import argparse
import hashlib
import json
import math
import socket
import struct
import sys
import time
import wave

try:
    import audioop  # stdlib; used only for WAV format conversion
except Exception:
    audioop = None

SAMPLE_RATE = 48000
CHANNELS = 2
BITS = 16
CHUNK_MS = 20  # send 20 ms at a time, paced to real time


def sha256_hex(s: str) -> str:
    if not s:
        return ""
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def handshake(sock, password):
    hello = {
        "magic": "LANMED01",
        "version": 1,
        "auth": sha256_hex(password),
        "sampleRate": SAMPLE_RATE,
        "channels": CHANNELS,
        "bits": BITS,
    }
    sock.sendall((json.dumps(hello) + "\n").encode("utf-8"))
    # read one reply line
    buf = b""
    while not buf.endswith(b"\n"):
        b = sock.recv(1)
        if not b:
            raise RuntimeError("receiver closed during handshake")
        buf += b
    reply = json.loads(buf.decode("utf-8").strip())
    if not reply.get("ok"):
        raise RuntimeError("rejected by receiver: %s" % reply.get("error", "unknown"))
    print("Handshake OK — streaming (Ctrl-C to stop)")


def tone_frames(hz, seconds):
    """Yield ~20ms chunks of a stereo S16LE sine tone."""
    frames_per_chunk = SAMPLE_RATE * CHUNK_MS // 1000
    total_chunks = None if seconds is None else int(seconds * 1000 / CHUNK_MS)
    n = 0
    amp = 12000
    idx = 0
    while total_chunks is None or n < total_chunks:
        out = bytearray()
        for _ in range(frames_per_chunk):
            v = int(amp * math.sin(2 * math.pi * hz * (idx / SAMPLE_RATE)))
            out += struct.pack("<hh", v, v)  # L, R
            idx += 1
        yield bytes(out)
        n += 1


def wav_frames(path):
    """Yield ~20ms chunks of a WAV converted to 48k/stereo/S16LE."""
    if audioop is None:
        raise RuntimeError("audioop not available; use --tone instead")
    w = wave.open(path, "rb")
    sw, ch, sr = w.getsampwidth(), w.getnchannels(), w.getframerate()
    state = None
    frames_per_chunk = sr * CHUNK_MS // 1000
    while True:
        data = w.readframes(frames_per_chunk)
        if not data:
            break
        if sw != 2:
            data = audioop.lin2lin(data, sw, 2)
        if ch == 1:
            data = audioop.tostereo(data, 2, 1, 1)
        elif ch > 2:
            raise RuntimeError("only mono/stereo WAV supported")
        if sr != SAMPLE_RATE:
            data, state = audioop.ratecv(data, 2, 2, sr, SAMPLE_RATE, state)
        yield data
    w.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ip")
    ap.add_argument("--port", type=int, default=45788)
    ap.add_argument("--password", default="")
    ap.add_argument("--wav")
    ap.add_argument("--seconds", type=float, default=None,
                    help="stop after N seconds (tone mode); default: run until Ctrl-C")
    ap.add_argument("--tone", type=float, default=440.0)
    args = ap.parse_args()

    print("Connecting to %s:%d …" % (args.ip, args.port))
    sock = socket.create_connection((args.ip, args.port), timeout=5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    handshake(sock, args.password)

    gen = wav_frames(args.wav) if args.wav else tone_frames(args.tone, args.seconds)
    chunk_dt = CHUNK_MS / 1000.0
    next_t = time.monotonic()
    sent = 0
    try:
        for chunk in gen:
            sock.sendall(chunk)
            sent += len(chunk)
            next_t += chunk_dt
            delay = next_t - time.monotonic()
            if delay > 0:
                time.sleep(delay)
    except KeyboardInterrupt:
        print("\nStopped.")
    except (BrokenPipeError, ConnectionResetError):
        print("\nReceiver disconnected.")
    finally:
        print("Sent %.1f MB" % (sent / 1e6))
        sock.close()


if __name__ == "__main__":
    main()
