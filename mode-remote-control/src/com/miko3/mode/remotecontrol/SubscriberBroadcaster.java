package com.miko3.mode.remotecontrol;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared subscribe/broadcast/prune machinery for a one-broadcaster/N-readers
 * stream (KTD4/KTD8): MjpegBroadcaster (multipart JPEG frames) and
 * AudioBroadcaster (raw PCM) differ only in what one subscriber's write
 * looks like, not in how subscribers are tracked, snapshotted under lock, or
 * pruned on a write failure.
 */
abstract class SubscriberBroadcaster {
    private final String tag;
    private final List<OutputStream> subscribers = new ArrayList<OutputStream>();
    private final Map<OutputStream, Runnable> disconnectListeners = new HashMap<OutputStream, Runnable>();

    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    SubscriberBroadcaster(String tag) {
        this.tag = tag;
    }

    /** onDisconnected fires once, off the caller's thread, the first time a write to
     * `out` fails — the connection handler holding `out` open uses this to know when
     * it may return (and let RoutingHttpServer close the socket) instead of polling. */
    void subscribe(OutputStream out, Runnable onDisconnected) {
        synchronized (subscribers) {
            subscribers.add(out);
            disconnectListeners.put(out, onDisconnected);
        }
        Log.i(tag, "subscribed, count=" + subscribers.size());
    }

    boolean hasSubscribers() {
        synchronized (subscribers) {
            return !subscribers.isEmpty();
        }
    }

    /** Writes one payload to every current subscriber via `writer`; subscribers whose
     * write fails are pruned rather than tearing anything else down. */
    final void broadcast(Writer writer) {
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
                    writer.write(out);
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
            Log.i(tag, "pruned " + dead.size() + " disconnected subscriber(s), count=" + subscribers.size());
            for (Runnable listener : toNotify) {
                listener.run();
            }
        }
    }
}
