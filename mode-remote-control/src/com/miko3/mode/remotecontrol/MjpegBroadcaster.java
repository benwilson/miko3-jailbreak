package com.miko3.mode.remotecontrol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fans one Camera2 capture session's JPEG frames out to every connected
 * MJPEG viewer (on-device WebView plus any remote browser), per KTD4 — a
 * single shared capture session, not one per viewer.
 */
final class MjpegBroadcaster extends SubscriberBroadcaster {
    private static final String BOUNDARY = "frame";

    MjpegBroadcaster() {
        super("MjpegBroadcaster");
    }

    String contentType() {
        return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
    }

    /** Pushes one JPEG frame to every current subscriber; disconnected ones are pruned. */
    void publishFrame(final byte[] jpeg) {
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n\r\n";
        final byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        broadcast(new Writer() {
            @Override
            public void write(OutputStream out) throws IOException {
                out.write(headerBytes);
                out.write(jpeg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        });
    }
}
