package com.miko3.shared;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes an HTTP/1.1 "Transfer-Encoding: chunked" body from the given raw
 * stream. Needed for the operator-mic-to-robot-speaker (KTD7) and
 * operator-video-to-robot-screen uploads, which browsers send as chunked
 * POST bodies of indeterminate total length (a live MediaRecorder feed,
 * not a single fixed-size upload).
 */
final class ChunkedInputStream extends InputStream {
    private final InputStream raw;
    private long remainingInChunk;
    private boolean finished;

    ChunkedInputStream(InputStream raw) {
        this.raw = raw;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (finished) {
            return -1;
        }
        if (remainingInChunk == 0) {
            remainingInChunk = readChunkSize();
            if (remainingInChunk == 0) {
                readLine(); // trailing CRLF after the terminating 0-size chunk
                finished = true;
                return -1;
            }
        }
        int toRead = (int) Math.min(len, remainingInChunk);
        int n = raw.read(buf, off, toRead);
        if (n == -1) {
            finished = true;
            return -1;
        }
        remainingInChunk -= n;
        if (remainingInChunk == 0) {
            readLine(); // CRLF that terminates this chunk's data
        }
        return n;
    }

    private long readChunkSize() throws IOException {
        String line = readLine();
        if (line == null) {
            finished = true;
            return 0;
        }
        int semi = line.indexOf(';'); // chunk extensions, ignored
        String sizeHex = (semi >= 0 ? line.substring(0, semi) : line).trim();
        if (sizeHex.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(sizeHex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("malformed chunk size: " + line);
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = raw.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return any ? sb.toString() : null;
    }
}
