package com.miko3.launcher;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.Json;
import com.miko3.shared.PageToken;
import com.miko3.shared.VoiceDirection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ears probe (meeting plan U1, step 4; R2, R21, KTD4, KTD13): the route on
 * the TLS settings path behind which the owner's measurement session (U2)
 * reads what the robot hears. A POST carrying the page token and the nonce
 * runs one bounded capture through the launcher's own microphone and
 * recogniser (ListenEngine.probe) and answers per-second rows of
 *   angle       the median of the direction samples drained that second, or null,
 *   rms         the capture level in 16-bit terms,
 *   decode_ms   time spent in the recogniser that second (and its worst chunk),
 *   words       how many words the transcript holds so far, and
 *   matched     whether every word of the phrase the script sent is in it,
 * after the direction "backend" (NONE, CONEXANT or NC) and "raw_reply", the NC
 * chip's first raw reply in lowercase hex ("58585542..."), or null.
 *
 * Gate: it answers only while the debug system property PROPERTY holds the
 * per-run nonce scripts/qa-ears-probe.py generated, and only for WINDOW_MS
 * after that value was first read; anything else (no property, a GET, a wrong
 * nonce, a missing or stale page token, the window passed) is a 404, so the
 * route does not exist to anyone who did not arm it. The nonce and the token
 * travel in the body, never the URL, which the server logs.
 *
 * Privacy (R21, "judged on the robot"): the transcript is read only to count
 * words and test the phrase, and nothing here logs. No text leaves in the
 * body, a status line or a URL.
 *
 * Plain Java with no android.* imports: scripts/tests/test_launcher_settings.py
 * drives it under the settings page harness with fakes for the property, the
 * clock, the direction and the capture.
 */
final class EarsProbe {
    /** The debug property the probe script sets to its nonce and clears on exit. */
    static final String PROPERTY = "debug.miko3.ears_probe";
    /** How long the property arms the route after it was first read. */
    static final long WINDOW_MS = 15 * 60 * 1000L;
    static final int DEFAULT_SECONDS = 10;
    /** One capture is one capped listen, so it cannot outlast the listen cap. */
    static final int MAX_SECONDS = (int) (ListenSession.MAX_CAP_MS / 1000);
    /** A row closes once this much audio has reached the recogniser. */
    static final int SAMPLES_PER_ROW = ListenSession.SAMPLE_RATE;

    /** android.os.SystemProperties, as far as the gate cares. */
    interface Props {
        /** The property's value, or "" when unset. */
        String get(String key);
    }

    interface Clock {
        long nowMs();
    }

    /** The direction backend: which one answered, and its samples since the last drain. */
    interface Direction {
        /** The backend's name: NONE, CONEXANT or NC (VoiceDirection.Backend). */
        String backend();

        /** The chip's first raw reply in hex, or null (explore plan U2: U1 places the reply CRC from it). */
        default String rawReply() {
            return null;
        }

        /** The chip's latest raw value (0 to 255), or null: each row carries it so
         * qa-direction-chip.py --calibrate can fit zero, sign and scale (hey-miko plan KTD12). */
        default Integer raw() {
            return null;
        }

        /** Angle readings since the last call, oldest first; null for a failed read. */
        List<Float> drain();
    }

    /** Runs one capture with the tap on the microphone and the recogniser and returns
     * its rows. Throws IllegalStateException when the microphone is busy or the
     * recogniser is not ready (ListenSession.REFUSE_*). */
    interface Runner {
        List<Row> capture(Tap tap, int seconds) throws Exception;
    }

    /** One second of the capture. Carries counts and flags only, never words. */
    static final class Row {
        final int second;
        final Float angle;
        final int rms;
        final long decodeMs;
        final long decodeMaxMs;
        final int chunks;
        final int words;
        final boolean matched;
        final Integer raw;

        Row(int second, Float angle, int rms, long decodeMs, long decodeMaxMs, int chunks, int words,
            boolean matched, Integer raw) {
            this.raw = raw;
            this.second = second;
            this.angle = angle;
            this.rms = rms;
            this.decodeMs = decodeMs;
            this.decodeMaxMs = decodeMaxMs;
            this.chunks = chunks;
            this.words = words;
            this.matched = matched;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("second", second);
            m.put("angle", angle == null ? null : Double.valueOf(angle));
            m.put("rms", rms);
            m.put("decode_ms", decodeMs);
            m.put("decode_max_ms", decodeMaxMs);
            m.put("chunks", chunks);
            m.put("words", words);
            m.put("matched", matched);
            if (raw != null) {
                m.put("raw", raw);
            }
            return m;
        }
    }

    /**
     * Wraps one listen's microphone and recogniser (ListenSession.capture's loop:
     * mic.read then rec.accept, chunk by chunk). The microphone wrapper sums the
     * capture level; the recogniser wrapper times each decode and closes a row
     * every SAMPLES_PER_ROW samples, reading the transcript once for its counts.
     * isEndpoint() is always false so the capture runs to its cap.
     */
    static final class Tap {
        private final Direction direction;
        private final Set<String> phrase;
        private final List<Row> rows = new ArrayList<Row>();
        private ListenSession.Recognizer rec;
        private double sumSquares;
        private long samples;
        private long decodeNs;
        private long decodeMaxNs;
        private int chunks;
        private int second;

        Tap(Direction direction, String phrase) {
            this.direction = direction;
            this.phrase = words(phrase);
        }

        ListenSession.Mic mic(final ListenSession.Mic real) {
            return new ListenSession.Mic() {
                @Override
                public int read(float[] buf) {
                    int n = real.read(buf);
                    for (int i = 0; i < n; i++) {
                        sumSquares += (double) buf[i] * buf[i];
                    }
                    return n;
                }

                @Override
                public void close() {
                    real.close();
                }
            };
        }

        ListenSession.Recognizer recognizer(final ListenSession.Recognizer real) {
            rec = real;
            return new ListenSession.Recognizer() {
                @Override
                public void accept(float[] s, int n) {
                    long t0 = System.nanoTime();
                    real.accept(s, n);
                    long took = System.nanoTime() - t0;
                    decodeNs += took;
                    decodeMaxNs = Math.max(decodeMaxNs, took);
                    chunks++;
                    samples += n;
                    if (samples >= SAMPLES_PER_ROW) {
                        closeRow();
                    }
                }

                @Override
                public boolean isEndpoint() {
                    return false;
                }

                @Override
                public String text() {
                    return real.text();
                }

                @Override
                public void finish() {
                    real.finish();
                }

                @Override
                public void close() {
                    // Close the last partial second while the recogniser can still be read:
                    // after real.close() its native stream is released, and reading it in
                    // rows() segfaulted the launcher on the robot (2026-09-29).
                    synchronized (Tap.this) {
                        if (chunks > 0) {
                            closeRow();
                        }
                        rec = null;
                    }
                    real.close();
                }
            };
        }

        /** The rows so far, with the partial last second closed if it heard anything;
         * after the recogniser closed, the last second was already closed by close(). */
        synchronized List<Row> rows() {
            if (chunks > 0 && rec != null) {
                closeRow();
            }
            return new ArrayList<Row>(rows);
        }

        private synchronized void closeRow() {
            String text = rec == null ? null : rec.text();
            Set<String> heard = words(text);
            boolean matched = !phrase.isEmpty() && heard.containsAll(phrase);
            int rms = samples == 0 ? 0 : (int) Math.round(Math.sqrt(sumSquares / samples) * 32768);
            rows.add(new Row(++second, VoiceDirection.median(direction.drain()), rms, decodeNs / 1000000L,
                    decodeMaxNs / 1000000L, chunks, heard.isEmpty() ? 0 : wordCount(text), matched,
                    direction.raw()));
            sumSquares = 0;
            samples = 0;
            decodeNs = 0;
            decodeMaxNs = 0;
            chunks = 0;
        }
    }

    private final Props props;
    private final Clock clock;
    private final PageToken token;
    private final Direction direction;
    private final Runner runner;
    /** The property value the window was opened for, and when. Guarded by this. */
    private String armedNonce;
    private long firstReadMs;

    EarsProbe(Props props, Clock clock, PageToken token, Direction direction, Runner runner) {
        this.props = props;
        this.clock = clock;
        this.token = token;
        this.direction = direction;
        this.runner = runner;
    }

    /** The nonce the route answers to right now, or null: the property's value while it
     * is set and less than WINDOW_MS has passed since that value was first read. A new
     * value is a new run and opens a new window. */
    synchronized String armedNonce(long now) {
        String v = props.get(PROPERTY);
        if (v == null || v.isEmpty()) {
            armedNonce = null;
            return null;
        }
        if (!v.equals(armedNonce)) {
            armedNonce = v;
            firstReadMs = now;
        }
        return now - firstReadMs < WINDOW_MS ? v : null;
    }

    /** The route. Everything but an armed, tokened POST with the right nonce is a 404. */
    void handle(HttpRequest req, HttpResponse res) throws IOException {
        String nonce = armedNonce(clock.nowMs());
        if (nonce == null || !"POST".equals(req.method)) {
            notFound(res);
            return;
        }
        Map<String, String> form = SettingsPage.readForm(req);
        if (form == null || !token.check(form.get("t")) || !same(nonce, form.get("nonce"))) {
            notFound(res);
            return;
        }
        int seconds = Math.max(1, Math.min(MAX_SECONDS, parseInt(form.get("seconds"), DEFAULT_SECONDS)));
        List<Row> rows;
        try {
            rows = runner.capture(new Tap(direction, form.get("phrase")), seconds);
        } catch (IllegalStateException e) {
            res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8",
                    "probe refused: " + e.getMessage() + "\n");
            return;
        } catch (Exception e) {
            // The class only: a message could carry anything.
            res.sendText(500, "Internal Server Error", "text/plain; charset=utf-8",
                    "probe failed: " + e.getClass().getSimpleName() + "\n");
            return;
        }
        res.sendText(200, "OK", "application/json; charset=utf-8", json(direction.backend(), direction.rawReply(), seconds, rows));
    }

    private static void notFound(HttpResponse res) throws IOException {
        res.sendText(404, "Not Found", "text/plain; charset=utf-8", "not found\n");
    }

    static String json(String backend, String rawReply, int seconds, List<Row> rows) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backend", backend);
        m.put("raw_reply", rawReply);
        m.put("seconds", seconds);
        List<Object> out = new ArrayList<Object>();
        for (Row r : rows) {
            out.add(r.json());
        }
        m.put("rows", out);
        return Json.write(m) + "\n";
    }

    private static boolean same(String expected, String presented) {
        return presented != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The distinct lower-cased words of text (letters, digits and apostrophes). */
    static Set<String> words(String text) {
        Set<String> out = new HashSet<String>();
        if (text != null) {
            for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) {
                if (!w.isEmpty()) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** How many words text holds, counting repeats. */
    static int wordCount(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (String w : text.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
