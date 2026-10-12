#!/usr/bin/env python3
"""A demonstration stand-in for `gliff-server --stdio`.

It speaks the gliff protocol on stdin and stdout and loops a recorded desktop
(`sample.h264`, next to this file) as a mirrored output. Input is read and
dropped: nothing runs behind the picture. Standard library only.

sshd runs it as the forced command of the demonstration account, so it is
also what answers the client's other commands (it asks for the keyboard
layout before starting the server): those get an empty answer.
"""

import os
import struct
import sys
import threading
import time

VERSION = 1
WIDTH, HEIGHT, FPS = 1280, 720, 30
OUTPUT = "DEMO-1"
# Frames the client may leave unacknowledged before the stream waits.
WINDOW = 8
# A session left open does not hold the host for ever.
MAX_SECONDS = 30 * 60
HELLO_SECONDS = 20

# Client messages this server looks at.
HELLO, FRAME_ACK, PING, BYE = 0, 6, 11, 12


def head(major, value):
    """A CBOR head, in its shortest form."""
    if value < 24:
        return bytes([major << 5 | value])
    for code, size in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if value < 1 << (8 * size):
            return bytes([major << 5 | code]) + value.to_bytes(size, "big")
    raise ValueError(value)


def cbor(value):
    if value is None:
        return b"\xf6"
    if value is True:
        return b"\xf5"
    if value is False:
        return b"\xf4"
    if isinstance(value, int):
        return head(0, value)
    if isinstance(value, bytes):
        return head(2, len(value)) + value
    if isinstance(value, str):
        data = value.encode()
        return head(3, len(data)) + data
    if isinstance(value, list):
        return head(4, len(value)) + b"".join(cbor(v) for v in value)
    raise TypeError(type(value))


def uint(data, pos):
    """The unsigned integer at `pos`, and where it ends."""
    info = data[pos] & 31
    if data[pos] >> 5 != 0:
        raise ValueError("expected an unsigned integer")
    if info < 24:
        return info, pos + 1
    size = 1 << (info - 24)
    return int.from_bytes(data[pos + 1:pos + 1 + size], "big"), pos + 1 + size


def read_exact(stream, count):
    data = b""
    while len(data) < count:
        chunk = stream.read(count - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def read_message(stream):
    """One frame: its variant and the fields' raw bytes. A payload is skipped."""
    body_len, payload_len = struct.unpack("<II", read_exact(stream, 8))
    if body_len > 1 << 20 or payload_len > 128 << 20:
        raise ValueError("frame too large")
    body = read_exact(stream, body_len)
    left = payload_len
    while left:
        left -= len(read_exact(stream, min(left, 1 << 16)))
    if body[0] != 0x82:
        raise ValueError("expected [variant, fields]")
    variant, pos = uint(body, 1)
    return variant, body[pos:]


def access_units(stream):
    """Split an Annex B stream into (bytes, keyframe) pictures."""
    nals = []
    i = 0
    while i + 3 < len(stream):
        if stream[i] == 0 and stream[i + 1] == 0 and stream[i + 2] == 1:
            nals.append((i - 1 if i > 0 and stream[i - 1] == 0 else i, i + 3))
            i += 3
        else:
            i += 1
    units = []
    start, has_slice, keyframe = -1, False, False
    for begin, header in nals:
        kind = stream[header] & 0x1f
        is_slice = kind in (1, 5)
        # first_mb_in_slice == 0 is a leading 1 bit: a new picture.
        first = is_slice and stream[header + 1] & 0x80 != 0
        if has_slice and (not is_slice or first):
            units.append((stream[start:begin], keyframe))
            start, has_slice, keyframe = -1, False, False
        if start < 0:
            start = begin
        has_slice = has_slice or is_slice
        keyframe = keyframe or kind == 5
    if start >= 0 and has_slice:
        units.append((stream[start:], keyframe))
    return units


def serve():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "sample.h264"), "rb") as f:
        units = access_units(f.read())

    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer
    lock = threading.Lock()

    def send(variant, fields, payload=b""):
        body = cbor([variant, fields])
        with lock:
            stdout.write(struct.pack("<II", len(body), len(payload)) + body + payload)
            stdout.flush()

    def now_ms():
        return int(time.time() * 1000)

    # Whoever connects and says nothing is not a client.
    silent = threading.Timer(HELLO_SECONDS, lambda: os._exit(1))
    silent.daemon = True
    silent.start()
    variant, fields = read_message(stdin)
    silent.cancel()
    if variant != HELLO:
        sys.exit("demo-server: expected Hello")
    if fields[0] >> 5 != 4:
        sys.exit("demo-server: malformed Hello")
    version, _ = uint(fields, 1)
    if version != VERSION:
        # Error: code 1 is a version mismatch.
        send(9, [1, "This demonstration server speaks protocol %d, the client %d" % (VERSION, version), VERSION])
        sys.exit(1)

    # HelloAck: a mirrored output, so the client draws the pointer itself.
    send(0, [VERSION, [False, OUTPUT], [[OUTPUT, WIDTH, HEIGHT, 1000]], []])
    send(12, [now_ms()])  # Ping
    # StreamConfig: H.264, one 4:2:0 stream, encoded on the CPU, no extradata.
    send(1, [1, 2, 1, WIDTH, HEIGHT, 1000, b"", None, WIDTH, HEIGHT, FPS])

    window = threading.Semaphore(WINDOW)

    def reader():
        try:
            while True:
                variant, fields = read_message(stdin)
                if variant == FRAME_ACK:
                    window.release()
                elif variant == PING:
                    t, _ = uint(fields, 1)
                    send(8, [t, now_ms()])  # Pong
                elif variant == BYE:
                    break
        except (EOFError, ValueError, IndexError, OSError):
            pass
        os._exit(0)

    threading.Thread(target=reader, daemon=True).start()

    start = time.monotonic()
    frame = 0
    while True:
        for data, keyframe in units:
            window.acquire()
            frame += 1
            elapsed = time.monotonic() - start
            if elapsed > MAX_SECONDS:
                os._exit(0)
            # VideoFrame: id, pts in microseconds, keyframe, damage, data and aux lengths.
            send(2, [frame, int(elapsed * 1_000_000), keyframe, [], len(data), 0], data)
            wait = start + frame / FPS - time.monotonic()
            if wait > 0:
                time.sleep(wait)


if __name__ == "__main__":
    # Under sshd's ForceCommand, what the client asked for is in the environment.
    asked = os.environ.get("SSH_ORIGINAL_COMMAND")
    if asked is not None and "--stdio" not in asked:
        sys.exit(0)
    try:
        serve()
    except (EOFError, BrokenPipeError):
        pass
