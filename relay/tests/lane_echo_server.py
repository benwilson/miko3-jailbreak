#!/usr/bin/env python3
"""lane_echo_server.py -- RFC 6455 conformance target for the shared Java
WebSocketClient (U2).

Hand-rolled on asyncio streams rather than the `websockets` library, because
the scenarios need raw frame control the library deliberately hides: it has to
see whether each client frame was masked and which length form it used, send a
message split into fragments with a control frame between them, stop answering
pings, and drop the TCP connection without a close frame.

Echoes every text and binary message back as one unfragmented frame. A text
message starting with "!" is a command instead of an echo:

  !stats                       reply with a JSON text frame of counters
  !fragment text|binary N SIZE send one SIZE-byte message split into N frames,
                               with a ping between the first two fragments
  !silent                      keep reading, but stop echoing and stop
                               answering pings (a dead peer on a live socket)
  !deaf                        stop reading entirely (the client's writes
                               eventually block on a full TCP window)
  !abort                       reset the TCP connection with no close frame
  !close                       send close 1000, wait for the client's reply

Fragment payloads are deterministic so the client can check them: text is
FRAGMENT_TEXT_ALPHABET repeated, binary is byte i = i % 251.

A client frame without the mask bit is a protocol error (RFC 6455 5.1): the
server answers close 1002 and drops the connection, and counts it.

The request path /mute-handshake accepts the TCP connection but never answers
the upgrade, for the client's handshake-timeout scenario.

Usage: lane_echo_server.py [--host 127.0.0.1] [--port 0]
Prints "LISTENING <port>" on stdout once bound. Bind 0.0.0.0 to serve a
device on the LAN.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import socket
import struct
import sys

WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
FRAGMENT_TEXT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
MAX_MESSAGE_BYTES = 16 * 1024 * 1024

OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


def accept_key(key):
    digest = hashlib.sha1((key + WEBSOCKET_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def fragment_payload(kind, size):
    if kind == "text":
        reps = size // len(FRAGMENT_TEXT_ALPHABET) + 1
        return (FRAGMENT_TEXT_ALPHABET * reps)[:size].encode("ascii")
    return bytes(i % 251 for i in range(size))


def encode_frame(opcode, payload, fin=True):
    """Server frames are never masked (RFC 6455 5.1)."""
    head = bytearray([(0x80 if fin else 0) | opcode])
    n = len(payload)
    if n <= 125:
        head.append(n)
    elif n <= 0xFFFF:
        head.append(126)
        head += struct.pack("!H", n)
    else:
        head.append(127)
        head += struct.pack("!Q", n)
    return bytes(head) + payload


def new_counters():
    return {
        "frames": 0,
        "messages": 0,
        "pings": 0,
        "unmasked_rejections": 0,
        "protocol_errors": 0,
        "close_replies": 0,
        "client_closes": 0,
        "len_forms": {"7": 0, "16": 0, "64": 0},
    }


GLOBAL = new_counters()


def bump(conn, key, by=1):
    conn[key] += by
    GLOBAL[key] += by


class ProtocolError(Exception):
    pass


async def read_frame(reader):
    """Return (fin, opcode, masked, payload, len_form)."""
    b0, b1 = await reader.readexactly(2)
    masked = bool(b1 & 0x80)
    n = b1 & 0x7F
    form = "7"
    if n == 126:
        (n,) = struct.unpack("!H", await reader.readexactly(2))
        form = "16"
    elif n == 127:
        (n,) = struct.unpack("!Q", await reader.readexactly(8))
        form = "64"
    if n > MAX_MESSAGE_BYTES:
        raise ProtocolError(f"frame of {n} bytes")
    key = await reader.readexactly(4) if masked else None
    payload = await reader.readexactly(n)
    if key:
        payload = _unmask(payload, key)
    return bool(b0 & 0x80), b0 & 0x0F, masked, payload, form


def _unmask(payload, key):
    # One big-int XOR rather than a per-byte loop: 70 KB frames stay cheap.
    n = len(payload)
    if n == 0:
        return payload
    mask = (key * (n // 4 + 1))[:n]
    return (int.from_bytes(payload, "big") ^ int.from_bytes(mask, "big")).to_bytes(n, "big")


class Connection:
    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer
        self.stats = new_counters()
        self.silent = False
        self.closing = False

    async def send(self, opcode, payload, fin=True):
        self.writer.write(encode_frame(opcode, payload, fin))
        await self.writer.drain()

    def abort(self):
        sock = self.writer.get_extra_info("socket")
        if sock is not None:
            # SO_LINGER 0 turns close() into an RST: no FIN, no close frame.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        self.writer.transport.abort()

    async def run(self):
        partial_op = None
        partial = bytearray()
        while True:
            fin, opcode, masked, payload, form = await read_frame(self.reader)
            bump(self.stats, "frames")
            self.stats["len_forms"][form] += 1
            GLOBAL["len_forms"][form] += 1
            if not masked:
                bump(self.stats, "unmasked_rejections")
                await self.send(OP_CLOSE, struct.pack("!H", 1002) + b"unmasked client frame")
                self.writer.close()
                return
            if opcode == OP_PING:
                bump(self.stats, "pings")
                if not self.silent:
                    await self.send(OP_PONG, payload)
                continue
            if opcode == OP_PONG:
                continue
            if opcode == OP_CLOSE:
                if self.closing:
                    bump(self.stats, "close_replies")
                else:
                    bump(self.stats, "client_closes")
                    await self.send(OP_CLOSE, payload[:2])
                self.writer.close()
                return
            if opcode in (OP_TEXT, OP_BINARY):
                if partial_op is not None:
                    raise ProtocolError("new message inside a fragmented one")
                partial_op = opcode
                partial = bytearray(payload)
            elif opcode == OP_CONT:
                if partial_op is None:
                    raise ProtocolError("continuation with no message open")
                partial += payload
            else:
                raise ProtocolError(f"unknown opcode {opcode}")
            if not fin:
                continue
            op, data = partial_op, bytes(partial)
            partial_op, partial = None, bytearray()
            bump(self.stats, "messages")
            if op == OP_TEXT and data.startswith(b"!"):
                if await self.command(data.decode("utf-8")) is False:
                    return
            elif not self.silent:
                await self.send(op, data)

    async def command(self, line):
        parts = line.split()
        cmd = parts[0]
        if cmd == "!stats":
            await self.send(OP_TEXT, json.dumps({"conn": self.stats, "global": GLOBAL}).encode())
        elif cmd == "!fragment":
            kind, count, size = parts[1], int(parts[2]), int(parts[3])
            data = fragment_payload(kind, size)
            step = max(1, -(-size // count))
            chunks = [data[i:i + step] for i in range(0, size, step)] or [b""]
            for i, chunk in enumerate(chunks):
                opcode = (OP_TEXT if kind == "text" else OP_BINARY) if i == 0 else OP_CONT
                await self.send(opcode, chunk, fin=(i == len(chunks) - 1))
                if i == 0:
                    await self.send(OP_PING, b"mid-fragment")
        elif cmd == "!silent":
            self.silent = True
        elif cmd == "!deaf":
            await asyncio.sleep(3600)
            return False
        elif cmd == "!abort":
            self.abort()
            return False
        elif cmd == "!close":
            self.closing = True
            await self.send(OP_CLOSE, struct.pack("!H", 1000) + b"bye")
        else:
            await self.send(OP_TEXT, f"unknown command {cmd}".encode())
        return True


async def handle(reader, writer):
    conn = None
    try:
        head = await reader.readuntil(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        path = lines[0].split(" ")[1] if len(lines[0].split(" ")) > 1 else "/"
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        if path.startswith("/mute-handshake"):
            await asyncio.sleep(3600)
            return
        key = headers.get("sec-websocket-key")
        if not key or "websocket" not in headers.get("upgrade", "").lower():
            writer.write(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            await writer.drain()
            return
        writer.write(("HTTP/1.1 101 Switching Protocols\r\n"
                      "Upgrade: websocket\r\n"
                      "Connection: Upgrade\r\n"
                      f"Sec-WebSocket-Accept: {accept_key(key)}\r\n\r\n").encode("ascii"))
        await writer.drain()
        conn = Connection(reader, writer)
        await conn.run()
    except ProtocolError as e:
        if conn is not None:
            bump(conn.stats, "protocol_errors")
        print(f"protocol error: {e}", file=sys.stderr, flush=True)
    except (asyncio.IncompleteReadError, ConnectionError, asyncio.LimitOverrunError):
        pass
    finally:
        if not writer.transport.is_closing():
            writer.close()


async def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    server = await asyncio.start_server(handle, args.host, args.port)
    port = server.sockets[0].getsockname()[1]
    print(f"LISTENING {port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        os._exit(0)
