package com.miko3.shared;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A minimal RFC 6455 WebSocket connection, handed to a {@link
 * RoutingHttpServer.WebSocketHandler} after the upgrade handshake completes.
 * Text frames only (U13's drive-command stream never needs binary) — no
 * fragmentation/continuation support, since every message this protocol
 * sends is a single short control line, well under one TCP segment.
 */
public final class WebSocketConnection {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Blocks for the next text frame, transparently answering pings and
     * skipping pongs/unknown opcodes in between. Returns null on a clean
     * close frame or on the connection dropping (the two are handled
     * identically by every caller here: stop driving and clean up).
     */
    public String readText() throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                return null;
            }
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 == -1) {
                return null;
            }
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
            }
            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(maskKey);
            }
            // Frames this protocol ever receives are a handful of bytes (e.g.
            // "drive 20 0") — an int cast is safe; nothing here ever sends more.
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }
            switch (opcode) {
                case 0x1: // text
                    return new String(payload, StandardCharsets.UTF_8);
                case 0x8: // close
                    return null;
                case 0x9: // ping — answer in kind, then keep waiting for a real frame
                    sendFrame(0xA, payload);
                    break;
                default: // pong, continuation, or anything else this protocol never sends
                    break;
            }
        }
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Best-effort close frame, then the underlying socket. Never throws. */
    public void close() {
        try {
            sendFrame(0x8, new byte[0]);
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("unexpected EOF mid-frame");
        }
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) {
                throw new IOException("unexpected EOF mid-frame");
            }
            off += n;
        }
    }

    /** Server-to-client frames are never masked (RFC 6455 §5.1). */
    private void sendFrame(int opcode, byte[] payload) throws IOException {
        out.write(0x80 | (opcode & 0x0F)); // FIN=1, no fragmentation ever sent
        int len = payload.length;
        if (len <= 125) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((((long) len) >> (8 * i)) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }
}
