package com.miko3.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A minimal RFC 6455 WebSocket connection, used from both ends:
 * <ul>
 * <li>server side, handed to a {@link RoutingHttpServer.WebSocketHandler}
 * after the upgrade handshake (the remote-control mode's drive stream reads
 * text with {@link #readText()}); its sends are never masked;</li>
 * <li>client side, owned by {@link WebSocketClient}, which reads text and
 * binary with {@link #readMessage()}; its sends are always masked
 * (RFC 6455 §5.1).</li>
 * </ul>
 * Fragmented messages are reassembled before delivery, 2- and 8-byte payload
 * lengths are honored in both directions, and pings are answered in kind.
 * Sends are serialized by one lock, so frames from concurrent threads never
 * interleave on the wire. Every send is one unfragmented frame.
 */
public final class WebSocketConnection {
    /** A message larger than this is refused rather than buffered (the voice
     * lane's biggest frame is a few KB of audio). */
    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    /** One whole (reassembled) text or binary message. */
    public static final class Message {
        public final boolean binary;
        public final byte[] data;

        Message(boolean binary, byte[] data) {
            this.binary = binary;
            this.data = data;
        }

        /** The payload decoded as UTF-8 (meaningful for text messages). */
        public String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** Non-null only for a client-side connection: the source of mask keys. */
    private final SecureRandom maskRandom;
    private final ReentrantLock writeLock = new ReentrantLock();
    private boolean closeSent; // guarded by writeLock
    /** Written only by the reading thread; read by WebSocketClient's watchdog. */
    private volatile long pongsReceived;
    /** Status code and reason of the peer's close frame, once one arrives. */
    private volatile int peerCloseCode = -1;
    private volatile String peerCloseReason = "";

    /** Server side: sends unmasked, as RoutingHttpServer has always used it. */
    WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this(socket, in, out, false);
    }

    WebSocketConnection(Socket socket, InputStream in, OutputStream out, boolean clientSide) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.maskRandom = clientSide ? new SecureRandom() : null;
    }

    /**
     * Blocks for the next text frame, transparently answering pings and
     * skipping pongs and binary messages in between. Returns null on a clean
     * close frame or on the connection dropping (the two are handled
     * identically by every caller here: stop driving and clean up).
     */
    public String readText() throws IOException {
        while (true) {
            Message m = readMessage();
            if (m == null) {
                return null;
            }
            if (!m.binary) {
                return m.text();
            }
        }
    }

    /**
     * Blocks for the next whole text or binary message, reassembling
     * fragments and answering pings (even between fragments) along the way.
     * Returns null on the peer's close frame, which is answered before
     * returning, or on the connection dropping cleanly between frames. A
     * connection dropping mid-frame, or a message over
     * {@link #MAX_MESSAGE_BYTES}, throws IOException.
     */
    public Message readMessage() throws IOException {
        int messageOpcode = -1; // opcode of the fragmented message being reassembled
        ByteArrayOutputStream fragments = null;
        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                return null;
            }
            boolean fin = (b0 & 0x80) != 0;
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
            // A negative long here is a length with the top bit set: also too big.
            if (len < 0 || len > MAX_MESSAGE_BYTES
                    || (fragments != null && fragments.size() + len > MAX_MESSAGE_BYTES)) {
                throw new IOException("websocket message too large (" + len + " byte frame)");
            }
            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(maskKey);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }
            switch (opcode) {
                case OP_TEXT:
                case OP_BINARY:
                    if (fin) {
                        return new Message(opcode == OP_BINARY, payload);
                    }
                    messageOpcode = opcode;
                    fragments = new ByteArrayOutputStream(payload.length * 2);
                    fragments.write(payload, 0, payload.length);
                    break;
                case OP_CONTINUATION:
                    if (fragments == null) {
                        break; // stray continuation with no message open: ignore, as before
                    }
                    fragments.write(payload, 0, payload.length);
                    if (fin) {
                        return new Message(messageOpcode == OP_BINARY, fragments.toByteArray());
                    }
                    break;
                case OP_CLOSE:
                    if (payload.length >= 2) {
                        peerCloseCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        peerCloseReason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                    } else {
                        peerCloseCode = 1005; // RFC 6455 §7.1.5: no status code present
                    }
                    // Answer in kind (echoing the status code), once; close() then
                    // only closes the socket.
                    sendClose(payload.length >= 2 ? new byte[] {payload[0], payload[1]} : new byte[0], true);
                    return null;
                case OP_PING: // answer in kind, then keep waiting for a real frame
                    sendFrame(OP_PONG, payload, 0, payload.length);
                    break;
                case OP_PONG:
                    pongsReceived++;
                    break;
                default: // anything else this protocol never sends
                    break;
            }
        }
    }

    public void sendText(String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        sendFrame(OP_TEXT, b, 0, b.length);
    }

    public void sendBinary(byte[] data, int off, int len) throws IOException {
        sendFrame(OP_BINARY, data, off, len);
    }

    void sendPing(byte[] payload) throws IOException {
        sendFrame(OP_PING, payload, 0, payload.length);
    }

    long pongsReceived() {
        return pongsReceived;
    }

    /** The peer's close status code, 1005 if its close frame had none, or -1
     * if no close frame has arrived. */
    int peerCloseCode() {
        return peerCloseCode;
    }

    String peerCloseReason() {
        return peerCloseReason;
    }

    /**
     * Best-effort close frame (unless one was already sent, e.g. answering the
     * peer's), then the underlying socket. Never throws. Never blocks for
     * long either: if another thread is stuck mid-send on a full TCP window,
     * the close frame is skipped and closing the socket unblocks that sender.
     */
    public void close() {
        try {
            sendClose(new byte[0], false);
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void sendClose(byte[] payload, boolean waitForLock) throws IOException {
        boolean locked;
        if (waitForLock) {
            writeLock.lock();
            locked = true;
        } else {
            try {
                locked = writeLock.tryLock(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                locked = false;
            }
        }
        if (!locked) {
            return;
        }
        try {
            if (closeSent) {
                return;
            }
            closeSent = true;
            writeFrameLocked(OP_CLOSE, payload, 0, payload.length);
        } finally {
            writeLock.unlock();
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

    private void sendFrame(int opcode, byte[] payload, int off, int len) throws IOException {
        writeLock.lock();
        try {
            if (closeSent) {
                throw new IOException("websocket already closed");
            }
            writeFrameLocked(opcode, payload, off, len);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes one FIN frame as a single buffer (one syscall, and one unit the
     * lock keeps whole). Server-to-client frames are never masked; client
     * frames always are, with a fresh key per frame (RFC 6455 §5.1, §5.3).
     */
    private void writeFrameLocked(int opcode, byte[] payload, int off, int len) throws IOException {
        int headerLen = 2 + (len <= 125 ? 0 : len <= 0xFFFF ? 2 : 8) + (maskRandom != null ? 4 : 0);
        byte[] frame = new byte[headerLen + len];
        int p = 0;
        frame[p++] = (byte) (0x80 | (opcode & 0x0F)); // FIN=1, no fragmentation ever sent
        int maskBit = maskRandom != null ? 0x80 : 0;
        if (len <= 125) {
            frame[p++] = (byte) (maskBit | len);
        } else if (len <= 0xFFFF) {
            frame[p++] = (byte) (maskBit | 126);
            frame[p++] = (byte) ((len >> 8) & 0xFF);
            frame[p++] = (byte) (len & 0xFF);
        } else {
            frame[p++] = (byte) (maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                frame[p++] = (byte) ((((long) len) >> (8 * i)) & 0xFF);
            }
        }
        if (maskRandom != null) {
            byte[] key = new byte[4];
            maskRandom.nextBytes(key);
            System.arraycopy(key, 0, frame, p, 4);
            p += 4;
            for (int i = 0; i < len; i++) {
                frame[p + i] = (byte) (payload[off + i] ^ key[i & 3]);
            }
        } else {
            System.arraycopy(payload, off, frame, p, len);
        }
        out.write(frame);
        out.flush();
    }
}
