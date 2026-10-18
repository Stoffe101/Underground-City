#!/usr/bin/env python3
"""Ask a Minecraft 1.20.1 server whether it demands authentication before it accepts a login (D-090).

An authenticating server (``online-mode=true``) answers the very first Login Start with an *Encryption Request*: the client must then prove, through
Mojang's session server, that it owns the account. A server in ``online-mode=false`` accepts whatever name and UUID the client claims. This probe sends
a Forge-style handshake and a Login Start under a made-up name and reports which of the two it got. It sends no credentials and never gets past the
first reply, so it is safe to point at any server you run.

    python scripts/probe_login.py --host 127.0.0.1 --port 25565 --expect authenticated

Exit code 0 when the observed behaviour is the expected one. This proves the server REFUSES unauthenticated identities; it cannot prove that a real
launcher account gets in (that needs a Microsoft account and is part of the manual Windows playtest).
"""
from __future__ import annotations

import argparse
import hashlib
import socket
import struct
import sys
import uuid

PROTOCOL_1_20_1 = 763


def offline_uuid(name: str) -> uuid.UUID:
    """Java's ``UUID.nameUUIDFromBytes("OfflinePlayer:<name>")``: the MD5 of the string alone, with the version-3 and variant bits set (no namespace prefix)."""
    raw = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(raw))


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def string(text: str) -> bytes:
    raw = text.encode("utf-8")
    return varint(len(raw)) + raw


def packet(packet_id: int, payload: bytes) -> bytes:
    body = varint(packet_id) + payload
    return varint(len(body)) + body


def read_varint(sock: socket.socket) -> int:
    value = 0
    for shift in range(0, 35, 7):
        byte = sock.recv(1)
        if not byte:
            raise ConnectionError("the server closed the connection")
        value |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return value
    raise ValueError("VarInt too long")


def read_packet(sock: socket.socket) -> tuple[int, bytes]:
    length = read_varint(sock)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("the server closed the connection mid-packet")
        data += chunk
    # Before compression is switched on a packet is VarInt id + payload.
    idx = 0
    value = 0
    for shift in range(0, 35, 7):
        byte = data[idx]; idx += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            break
    return value, data[idx:]


def probe(host: str, port: int, name: str, timeout: float) -> tuple[str, str]:
    """Returns (verdict, detail): verdict is 'authenticated' (Encryption Request), 'unauthenticated' (the login went on without one) or 'refused'."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        # Handshake: protocol, address (Forge marks its clients with \0FML3\0), port, next state 2 (login)
        sock.sendall(packet(0x00, varint(PROTOCOL_1_20_1) + string(host + "\0FML3\0") + struct.pack(">H", port) + varint(2)))
        # Login Start (1.20.1): name, has-uuid flag, uuid. The uuid is the offline uuid a dev client would claim: nothing proves it.
        claimed = offline_uuid(name)
        sock.sendall(packet(0x00, string(name) + b"\x01" + claimed.bytes))
        packet_id, payload = read_packet(sock)
        if packet_id == 0x01:
            return "authenticated", "Encryption Request: the client must prove it owns the account before the server goes on"
        if packet_id == 0x00:
            text = payload[1:].decode("utf-8", "replace")[:200]
            return "refused", "disconnected before any login step: " + text
        return "unauthenticated", f"packet 0x{packet_id:02x} came back without an Encryption Request: the login continues for whatever name and UUID the client claims"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--name", default="UcProbe")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--expect", choices=["authenticated", "unauthenticated"], default="authenticated")
    args = parser.parse_args()
    verdict, detail = probe(args.host, args.port, args.name, args.timeout)
    print(f"{args.host}:{args.port} -> {verdict}: {detail}")
    ok = verdict == args.expect
    print("RESULT:", "pass" if ok else "FAIL", f"(expected {args.expect})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
