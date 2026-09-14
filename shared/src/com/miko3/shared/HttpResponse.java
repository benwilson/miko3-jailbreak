package com.miko3.shared;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper over a connection's raw OutputStream. Supports the two
 * response shapes this server needs: a normal fixed-length response, and a
 * headers-only start for a handler that then streams its own body
 * indefinitely (MJPEG multipart frames, a raw-PCM audio feed) until the
 * client disconnects.
 */
public final class HttpResponse {
    private final OutputStream out;
    private boolean headersSent;

    public HttpResponse(OutputStream out) {
        this.out = out;
    }

    public void sendText(int status, String statusText, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendBytes(status, statusText, contentType, bytes);
    }

    public void sendBytes(int status, String statusText, String contentType, byte[] body) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.0 ").append(status).append(' ').append(statusText).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        headersSent = true;
    }

    /** Starts a streaming response with the given headers and no Content-Length; the
     * caller then writes its own body directly via rawOutputStream() until the client
     * disconnects or the caller stops. */
    public void startStreaming(int status, String statusText, String contentType, String extraHeaders) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.0 ").append(status).append(' ').append(statusText).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        if (extraHeaders != null) {
            headers.append(extraHeaders);
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        headersSent = true;
    }

    public OutputStream rawOutputStream() {
        return out;
    }

    public boolean isHeadersSent() {
        return headersSent;
    }
}
