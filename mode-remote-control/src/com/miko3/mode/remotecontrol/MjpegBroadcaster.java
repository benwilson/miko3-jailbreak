package com.miko3.mode.remotecontrol;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fans one Camera2 capture session's JPEG frames out to every connected
 * MJPEG viewer (on-device WebView plus any remote browser), per KTD4 — a
 * single shared capture session, not one per viewer. A subscriber that
 * disconnects (a write failure) is pruned rather than tearing anything
 * else down.
 */
final class MjpegBroadcaster {
    private static final String TAG = "MjpegBroadcaster";
    private static final String BOUNDARY = "frame";

    private final List<OutputStream> subscribers = new ArrayList<OutputStream>();
    private final Map<OutputStream, Runnable> disconnectListeners = new HashMap<OutputStream, Runnable>();

    String contentType() {
        return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
    }

    /** onDisconnected fires once, off the caller's thread, the first time a write to
     * `out` fails — the connection handler holding `out` open uses this to know when
     * it may return (and let RoutingHttpServer close the socket) instead of polling. */
    void subscribe(OutputStream out, Runnable onDisconnected) {
        synchronized (subscribers) {
            subscribers.add(out);
            disconnectListeners.put(out, onDisconnected);
        }
        Log.i(TAG, "viewer subscribed, count=" + subscribers.size());
    }

    /** Pushes one JPEG frame to every current subscriber; disconnected ones are pruned. */
    void publishFrame(byte[] jpeg) {
        List<OutputStream> snapshot;
        synchronized (subscribers) {
            if (subscribers.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<OutputStream>(subscribers);
        }
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        List<OutputStream> dead = null;
        for (OutputStream out : snapshot) {
            try {
                synchronized (out) {
                    out.write(headerBytes);
                    out.write(jpeg);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } catch (IOException e) {
                if (dead == null) {
                    dead = new ArrayList<OutputStream>();
                }
                dead.add(out);
            }
        }
        if (dead != null) {
            List<Runnable> toNotify = new ArrayList<Runnable>();
            synchronized (subscribers) {
                subscribers.removeAll(dead);
                for (OutputStream out : dead) {
                    Runnable listener = disconnectListeners.remove(out);
                    if (listener != null) {
                        toNotify.add(listener);
                    }
                }
            }
            Log.i(TAG, "pruned " + dead.size() + " disconnected viewer(s), count=" + subscribers.size());
            for (Runnable listener : toNotify) {
                listener.run();
            }
        }
    }
}
