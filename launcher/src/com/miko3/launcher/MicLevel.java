package com.miko3.launcher;

/**
 * The ears' minute microphone level line (owner 2026-10-02 at home: "his microphone has a hard
 * time hearing things"). Robot 2026-10-03: the line never appeared, because each Mic kept its own
 * window and the Mic is reopened more often than once a minute; one window shared by every Mic
 * (ListenEngine's static instance) survives the reopens. Each read adds its chunk's raw sums;
 * once WINDOW_MS has passed since the window began, add() returns the line and a new window
 * begins. Plain Java, proven in the listen-service harness.
 */
final class MicLevel {
    static final long WINDOW_MS = 60000;

    private long windowStart = Long.MIN_VALUE;
    private double sq;
    private long n;
    private int peak;
    private long clipped;

    /**
     * One chunk read at now: the sum of its raw samples' squares, their count, its raw peak, the
     * samples the gain clipped, and the gain. The level line when the window is over, else null.
     */
    synchronized String add(long now, double chunkSq, long chunkN, int chunkPeak, long chunkClipped, float gain) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = now;
        }
        sq += chunkSq;
        n += chunkN;
        peak = Math.max(peak, chunkPeak);
        clipped += chunkClipped;
        if (now - windowStart < WINDOW_MS) {
            return null;
        }
        long rms = n == 0 ? 0 : Math.round(Math.sqrt(sq / n));
        String line = "ears: mic level: RMS " + rms + ", peak " + peak + " (raw), gain x" + gain + ", clipped "
                + clipped + " of " + n + " samples";
        windowStart = now;
        sq = 0;
        n = 0;
        peak = 0;
        clipped = 0;
        return line;
    }
}
