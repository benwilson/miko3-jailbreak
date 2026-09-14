package com.miko3.mode.remotecontrol;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fans the robot's own microphone's raw PCM out to every connected
 * listener (KTD8), the same one-broadcaster/N-readers shape as
 * MjpegBroadcaster (U6) — structurally analogous, not multipart-framed
 * since this is a single continuous byte stream rather than discrete
 * frames.
 */
final class AudioBroadcaster {
    private static final String TAG = "AudioBroadcaster";

    private final List<OutputStream> subscribers = new ArrayList<OutputStream>();
    private final Map<OutputStream, Runnable> disconnectListeners = new HashMap<OutputStream, Runnable>();

    void subscribe(OutputStream out, Runnable onDisconnected) {
        synchronized (subscribers) {
            subscribers.add(out);
            disconnectListeners.put(out, onDisconnected);
        }
        Log.i(TAG, "listener subscribed, count=" + subscribers.size());
    }

    void publishChunk(byte[] pcm, int len) {
        List<OutputStream> snapshot;
        synchronized (subscribers) {
            if (subscribers.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<OutputStream>(subscribers);
        }
        List<OutputStream> dead = null;
        for (OutputStream out : snapshot) {
            try {
                synchronized (out) {
                    out.write(pcm, 0, len);
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
            for (Runnable listener : toNotify) {
                listener.run();
            }
        }
    }

    boolean hasSubscribers() {
        synchronized (subscribers) {
            return !subscribers.isEmpty();
        }
    }
}
