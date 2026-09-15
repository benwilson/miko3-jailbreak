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

    /** Actively closes every current subscriber's connection and fires their
     * disconnect listeners — unlike broadcast()'s own pruning (which only notices a
     * dead subscriber reactively, on its NEXT failed write), this is for a caller
     * that needs subscribers to find out NOW that the stream is over, even though
     * the underlying connection is otherwise still healthy and would just sit open
     * indefinitely waiting for a broadcast() that may never come (a multipart
     * stream idling with no new parts doesn't fire a client-side "error" or "end"
     * event on its own). Used by ModeApp's own /operator-video-stop route so the
     * robot's on-device idle-eyes page (DeviceViewPage) can tell "the operator
     * turned video off" apart from "no frame has arrived yet" and switch back to
     * the eyes instead of getting stuck showing the last frame forever. */
    final void disconnectAll() {
        List<OutputStream> snapshot;
        synchronized (subscribers) {
            snapshot = new ArrayList<OutputStream>(subscribers);
            subscribers.clear();
        }
        List<Runnable> toNotify = new ArrayList<Runnable>();
        synchronized (subscribers) {
            for (OutputStream out : snapshot) {
                Runnable listener = disconnectListeners.remove(out);
                if (listener != null) {
                    toNotify.add(listener);
                }
            }
        }
        for (OutputStream out : snapshot) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        Log.i(tag, "disconnectAll() closed " + snapshot.size() + " subscriber(s)");
        for (Runnable listener : toNotify) {
            listener.run();
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
