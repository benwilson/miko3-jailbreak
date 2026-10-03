package com.miko3.mode.explore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * The robot's learning log (2026-10-03): the brain's structured records, and only those,
 * appended to an app-private file a developer pulls between runs (scripts/pull-learn-log.py)
 * and reads with scripts/nav-report.py, chat-report.py and daily-diff.py. The robot never
 * reads it back: it gathers, the code is improved off the robot.
 *
 * Each line is written exactly as `adb logcat -v time` would show it ("MM-dd HH:mm:ss.SSS
 * I/Tag( pid): message"), so every script reads a pulled learn.log and a logcat capture alike.
 * Which notes go in is a fixed list of prefixes (RECORD_PREFIXES): the per-decision records
 * (leg:, trig:, turn:, act:, task:, escape#, seek#, mode:, floor:), the episodes nav-report counts (bathroom,
 * power, docked, stalls, recover, jams), and this log's own "learn:" lines. None of them
 * carries a word said or heard, a name or a pixel.
 *
 * Cheap on the robot: offer() only checks the prefix and queues (never blocks, never touches
 * the disk); one daemon thread writes in batches, writes "learn: alive" after a minute with
 * nothing else (so a quiet stretch never reads as a capture gap), and rotates the file at
 * maxBytes, keeping one old one (learn.log.1). Plain Java, so the host harness tests it.
 */
final class LearnLog {
    static final String FILE_NAME = "learn.log";
    static final String OLD_SUFFIX = ".1";
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final long ALIVE_MS = 60000;
    /** Records waiting for the writer; past this they are dropped (and counted), never waited for. */
    static final int QUEUE_MAX = 2000;

    /** The notes that are records, by prefix. */
    static final String[] RECORD_PREFIXES = {
        "leg: ", "trig: ", "turn: ", "learn: ", "escape#", "seek#", "mode: ", "act: ", "task: ",
        "bathroom: ", "power: ", "docked: ", "docked look: something new", "on the charger: ", "eyes only:",
        "sensors available and lease held", "hazard ", "controller refused forward", "collision stop",
        "camera reads the way ahead blocked", "wheels stalled", "back-up stalled", "back-out stalled",
        "stall: waiting", "recover ", "free after ", "no recovery after", "the board is back",
        "jam probe", "fully jammed", "wedged: ", "boxed in: ", "cornered:", "asking for help:",
        "wriggle", "measured turn", "curiosity stop:", "remarks in the last", "conversation over",
        "answering the call", "coverage: ", "place: seen before", "floor: ", "dark floor:",
    };

    /** Whether this note is a record that belongs in the learning log. */
    static boolean wanted(String message) {
        if (message == null) {
            return false;
        }
        for (String p : RECORD_PREFIXES) {
            if (message.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** One line as `logcat -v time` shows it, in this formatter's zone (the robot's local time). */
    static String line(SimpleDateFormat time, long wallMs, int pid, String tag, String message) {
        String p = String.valueOf(pid);
        StringBuilder b = new StringBuilder(message.length() + 48);
        b.append(time.format(new Date(wallMs))).append(" I/").append(tag).append('(');
        for (int i = p.length(); i < 5; i++) {
            b.append(' ');
        }
        // One record, one line: a newline inside a note never splits it.
        return b.append(p).append("): ").append(message.replace('\n', ' ').replace('\r', ' ')).append('\n')
                .toString();
    }

    static SimpleDateFormat timeFormat() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    }

    /** The clock the lines are stamped with (System.currentTimeMillis on the robot). */
    interface Wall {
        long ms();
    }

    private static final class Entry {
        final long wallMs;
        final String tag;
        final String message;

        Entry(long wallMs, String tag, String message) {
            this.wallMs = wallMs;
            this.tag = tag;
            this.message = message;
        }
    }

    private final File file;
    private final File old;
    private final int pid;
    private final long maxBytes;
    private final long aliveMs;
    private final Wall wall;
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<Entry>(QUEUE_MAX);
    private final SimpleDateFormat time = timeFormat();
    private final Thread writer;
    private volatile boolean closed;
    private volatile int dropped;

    /** Starts the writer; dir is the app's private files dir. */
    LearnLog(File dir, int pid) {
        this(dir, pid, MAX_BYTES, ALIVE_MS, new Wall() {
            @Override
            public long ms() {
                return System.currentTimeMillis();
            }
        });
    }

    LearnLog(File dir, int pid, long maxBytes, long aliveMs, Wall wall) {
        this.file = new File(dir, FILE_NAME);
        this.old = new File(dir, FILE_NAME + OLD_SUFFIX);
        this.pid = pid;
        this.maxBytes = maxBytes;
        this.aliveMs = aliveMs;
        this.wall = wall;
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writeLoop();
            }
        }, "explore-learn-log");
        writer.setDaemon(true);
        writer.start();
    }

    /** A note from tag: queued when it is a record; any thread, never blocks, never throws. */
    void offer(String tag, String message) {
        if (closed || !wanted(message)) {
            return;
        }
        if (!queue.offer(new Entry(wall.ms(), tag, message))) {
            dropped++;
        }
    }

    /** A line that is always kept (the start stamp): as offer(), without the prefix check. */
    void record(String tag, String message) {
        if (closed || message == null) {
            return;
        }
        if (!queue.offer(new Entry(wall.ms(), tag, message))) {
            dropped++;
        }
    }

    /** Stops the writer once it has written what is queued; the caller never waits for it. */
    void close() {
        close(0);
    }

    /**
     * Stops the writer once it has written what is queued, waiting up to waitMs (0: not at all)
     * for it. Review 2026-10-03: a STOP entry offered to a full queue was dropped silently, so the
     * writer ran on and the caller waited out waitMs; now the closed flag and an interrupt stop
     * it, whatever the queue holds.
     */
    void close(long waitMs) {
        if (closed) {
            return;
        }
        closed = true;
        writer.interrupt();
        if (waitMs <= 0) {
            return;
        }
        try {
            writer.join(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        FileOutputStream out = null;
        long size = file.length();
        StringBuilder batch = new StringBuilder();
        try {
            while (true) {
                Entry e;
                boolean stop = closed;
                if (stop) {
                    e = queue.poll();
                } else {
                    try {
                        e = queue.poll(aliveMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        // close(): what is queued is written below, then the writer stops.
                        stop = true;
                        e = queue.poll();
                    }
                }
                batch.setLength(0);
                if (e == null && !stop) {
                    batch.append(line(time, wall.ms(), pid, "ExploreLearn", "learn: alive"
                            + (dropped > 0 ? " dropped=" + dropped : "")));
                }
                while (e != null) {
                    batch.append(line(time, e.wallMs, pid, e.tag, e.message));
                    e = queue.poll();
                }
                if (!stop && closed) {
                    // close() came while this batch was being built: what it left queued goes too.
                    stop = true;
                    for (e = queue.poll(); e != null; e = queue.poll()) {
                        batch.append(line(time, e.wallMs, pid, e.tag, e.message));
                    }
                }
                if (batch.length() > 0) {
                    if (out != null && size >= maxBytes) {
                        out.close();
                        out = null;
                        rotate();
                        size = 0;
                    }
                    if (out == null) {
                        if (file.length() >= maxBytes) {
                            rotate();
                        }
                        out = new FileOutputStream(file, true);
                        size = file.length();
                    }
                    byte[] bytes = batch.toString().getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    out.flush();
                    size += bytes.length;
                }
                if (stop) {
                    break;
                }
            }
        } catch (IOException e) {
            // The disk refused: the log stops, Explore runs on (it never depends on this).
            closed = true;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }
    }

    private void rotate() {
        if (old.exists() && !old.delete()) {
            return;
        }
        if (!file.renameTo(old)) {
            file.delete();
        }
    }

    // ---- the start stamp ----

    /**
     * The tuning's fingerprint for the start record: "tuning=<8 hex> diff=<name=value;...>"
     * (diff "-" when every field is the shipped default). Every field of ExploreTuning,
     * by reflection, so a field added later is covered without touching this; the hash
     * tells two runs' tunings apart, the diff says how this one differs from the defaults.
     */
    static String tuningStamp(ExploreTuning tuning) {
        ExploreTuning defaults = new ExploreTuning.Builder().build();
        List<Field> fields = new ArrayList<Field>();
        for (Field f : ExploreTuning.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                fields.add(f);
            }
        }
        java.util.Collections.sort(fields, new java.util.Comparator<Field>() {
            @Override
            public int compare(Field a, Field b) {
                return a.getName().compareTo(b.getName());
            }
        });
        StringBuilder all = new StringBuilder();
        StringBuilder diff = new StringBuilder();
        for (Field f : fields) {
            f.setAccessible(true);
            String mine;
            String base;
            try {
                mine = describe(f.get(tuning));
                base = describe(f.get(defaults));
            } catch (IllegalAccessException e) {
                continue;
            }
            all.append(f.getName()).append('=').append(mine).append(';');
            if (!mine.equals(base)) {
                String v = mine.replace(' ', '_');
                diff.append(diff.length() == 0 ? "" : ";").append(f.getName()).append('=')
                        .append(v.length() > 80 ? v.substring(0, 80) : v);
            }
        }
        CRC32 crc = new CRC32();
        crc.update(all.toString().getBytes(StandardCharsets.UTF_8));
        String hex = Long.toHexString(crc.getValue());
        while (hex.length() < 8) {
            hex = "0" + hex;
        }
        return "tuning=" + hex + " diff=" + (diff.length() == 0 ? "-" : diff.toString());
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        } else if (v instanceof long[]) {
            return Arrays.toString((long[]) v);
        } else if (v instanceof int[]) {
            return Arrays.toString((int[]) v);
        } else if (v instanceof double[]) {
            return Arrays.toString((double[]) v);
        } else if (v instanceof float[]) {
            return Arrays.toString((float[]) v);
        } else if (v instanceof Object[]) {
            return Arrays.deepToString((Object[]) v);
        }
        return String.valueOf(v);
    }

    /** The start record: the build, then the tuning's stamp. */
    static String startRecord(String build, ExploreTuning tuning) {
        String b = build == null || build.trim().isEmpty() ? "-" : build.trim().replace(' ', '_');
        return "learn: start build=" + b + " " + tuningStamp(tuning);
    }
}
