package com.miko3.mode.voice;

import com.miko3.shared.WebSocketClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The "voice-lane-tx" thread: sends queued frames in order, each on the link
 * it was queued for. Uplink frames beyond Config.maxQueuedUplink drop the
 * oldest one (a stalled socket must not grow the queue without bound);
 * text frames are never dropped.
 */
final class LaneSender implements Runnable {
    private final int maxQueuedUplink;
    private final ConversationClient.Logger log;
    private final ArrayDeque<Frame> queue = new ArrayDeque<Frame>();
    private int binaries;
    private int dropped;
    private boolean finishing;
    private Thread thread;

    LaneSender(int maxQueuedUplink, ConversationClient.Logger log) {
        this.maxQueuedUplink = maxQueuedUplink;
        this.log = log;
    }

    synchronized void start() {
        thread = new Thread(this, "voice-lane-tx");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void text(WebSocketClient c, String text) {
        queue.add(new Frame(c, text, null, false));
        notifyAll();
    }

    synchronized void binary(WebSocketClient c, byte[] data) {
        if (c == null) {
            return;
        }
        if (binaries >= maxQueuedUplink) {
            for (Iterator<Frame> it = queue.iterator(); it.hasNext(); ) {
                if (it.next().isBinary()) {
                    it.remove();
                    binaries--;
                    break;
                }
            }
            dropped++;
            if (dropped == 1 || dropped % 50 == 0) {
                log.warn("uplink congested: " + dropped + " chunks dropped");
            }
        }
        queue.add(new Frame(c, null, data, false));
        binaries++;
        notifyAll();
    }

    /** Closes the link once everything queued before this has been sent. */
    synchronized void close(WebSocketClient c) {
        queue.add(new Frame(c, null, null, true));
        notifyAll();
    }

    /** Lets the queue drain for up to timeoutMs, then ends the thread. */
    void finish(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            finishing = true;
            notifyAll();
            long left;
            while (!queue.isEmpty() && (left = deadline - System.currentTimeMillis()) > 0) {
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Whatever is left is abandoned; close its links so no socket lingers.
            List<WebSocketClient> links = new ArrayList<WebSocketClient>();
            for (Frame item : queue) {
                if (!links.contains(item.link)) {
                    links.add(item.link);
                }
            }
            queue.clear();
            for (WebSocketClient c : links) {
                c.close();
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        while (true) {
            Frame item;
            synchronized (this) {
                while (queue.isEmpty()) {
                    if (finishing) {
                        return;
                    }
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                item = queue.poll();
                if (item.isBinary()) {
                    binaries--;
                }
            }
            WebSocketClient c = item.link;
            if (item.close) {
                c.close();
            } else if (item.text != null) {
                c.sendText(item.text);
            } else {
                c.sendBinary(item.data);
            }
            synchronized (this) {
                if (queue.isEmpty()) {
                    notifyAll(); // finish() waits for this
                }
            }
        }
    }

    /** One item of the sender's queue: a text frame, a binary (uplink) frame, or the
     * link's close, each bound to the link it was queued for. */
    private static final class Frame {
        final WebSocketClient link;
        final String text;
        final byte[] data;
        final boolean close;

        Frame(WebSocketClient link, String text, byte[] data, boolean close) {
            this.link = link;
            this.text = text;
            this.data = data;
            this.close = close;
        }

        boolean isBinary() {
            return data != null;
        }
    }
}
