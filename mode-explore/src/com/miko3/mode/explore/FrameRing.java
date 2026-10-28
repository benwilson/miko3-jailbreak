package com.miko3.mode.explore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Debug frame ring for camera calibration (log.tag.MikoExploreFrames=DEBUG):
 * each look's JPEG, as the camera gave it, saved as frame-&lt;wall ms&gt;.jpg in one
 * directory, the oldest deleted past capacity. scripts/calibrate-camera-fov.py
 * pairs the frames with the brain's measured turns by their wall-clock names.
 * Plain Java, one thread (the detect thread).
 */
final class FrameRing {
    static final String PREFIX = "frame-";
    static final String SUFFIX = ".jpg";

    private final File dir;
    private final int capacity;
    private ArrayDeque<File> kept;

    FrameRing(File dir, int capacity) {
        this.dir = dir;
        this.capacity = Math.max(1, capacity);
    }

    /** Writes jpeg as frame-&lt;wallMs&gt;.jpg and trims the ring; returns the name. */
    String save(long wallMs, byte[] jpeg) throws IOException {
        if (kept == null) {
            adopt();
        }
        File f = new File(dir, PREFIX + wallMs + SUFFIX);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(jpeg);
        } finally {
            out.close();
        }
        kept.addLast(f);
        while (kept.size() > capacity) {
            File old = kept.removeFirst();
            if (!old.delete() && old.exists()) {
                throw new IOException("could not delete " + old.getName());
            }
        }
        return f.getName();
    }

    /** The frames a previous run left, oldest first, join the ring. */
    private void adopt() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        kept = new ArrayDeque<>();
        File[] old = dir.listFiles();
        if (old == null) {
            return;
        }
        long[] times = new long[old.length];
        int n = 0;
        for (File f : old) {
            long t = timeOf(f.getName());
            if (t >= 0) {
                times[n++] = t;
            }
        }
        times = Arrays.copyOf(times, n);
        Arrays.sort(times);
        for (long t : times) {
            kept.addLast(new File(dir, PREFIX + t + SUFFIX));
        }
    }

    /** The wall ms in a ring frame's name, or -1 for any other file. */
    static long timeOf(String name) {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return -1;
        }
        try {
            return Long.parseLong(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
