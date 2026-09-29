package com.miko3.shared;

import com.example.conexantapi.ConexantDSP;
import com.example.conexantapi.NCDsp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The direction a voice comes from, read in our own process (meeting plan U1,
 * KTD4; explore plan U2, KTD10 to KTD12; docs/hardware/voice-mic.md section 3).
 * open() tries the Conexant backend through the vendor's JNI stub, then the
 * NC chip, remembers which answered, and angle() reads the direction of
 * arrival in degrees from it, or null when neither backend is there or the
 * read fails.
 *
 * The NC chip is tried only on the port the owner confirmed (KTD10): the
 * property PORT_PROPERTY, passed in through configure(). With the property
 * unset, or naming a node that does not exist, no NC native call is made at
 * all: a vendor call on a missing UART segfaulted the launcher
 * (docs/solutions/runtime-errors/vendor-dsp-direction-open-segfaults-launcher-miko3.md).
 * The vendor library only configures the port (initNCUART: 115200 8N1 raw;
 * 0 is success, 1 failure). The protocol then runs here in Java on our own
 * streams with a deadline on every reply (KTD11), because the vendor's native
 * reads block forever. Reads never block: buffered bytes are discarded before
 * each query, and the reply is polled with available() until it is in or the
 * deadline passes. Three misses in a row close the NC backend for the life of
 * the process. The raw value becomes signed degrees only once the calibration
 * properties are set (KTD12); until then the angle is NaN.
 *
 * The angle is meant to be sampled at a caller-set cadence on its own thread
 * (sample()), never from the capture thread, and reduced to a median over an
 * utterance rather than read once at its endpoint. Beyond turning DOA
 * reporting on once when the chip says it is off, nothing here sets DSP modes
 * or gains.
 *
 * Plain Java with no android.* imports. On a host JVM the stubs' static
 * initialisers fail to load the library; open() catches that and reports
 * Backend.NONE. scripts/tests/test_nc_frames.py drives the NC path through
 * openWith() with a fake native layer and a fake node.
 */
public final class VoiceDirection {
    public enum Backend { NONE, CONEXANT, NC }

    /** The library the stubs load, staged by scripts/build-custom-launcher.py. */
    public static final String LIBRARY = "conexant_dsp_lib";
    /** The confirmed NC port (KTD10), written by U1's script only after confirmation. */
    public static final String PORT_PROPERTY = "persist.miko3.voice_dir.port";
    /** The calibration (KTD12): the raw value straight ahead, +1 or -1, degrees per raw step. */
    public static final String ZERO_PROPERTY = "persist.miko3.voice_dir.zero";
    public static final String SIGN_PROPERTY = "persist.miko3.voice_dir.sign";
    public static final String SCALE_PROPERTY = "persist.miko3.voice_dir.scale";
    /** The vendor's NC buffer size (DSPSettings.openNCUART). */
    static final int NC_BUFFER = 500;
    /** How long a query waits for its reply, and how often it looks. */
    static final long REPLY_DEADLINE_MS = 80;
    static final long POLL_MS = 2;
    /** Misses in a row that close the NC backend. */
    static final int MAX_MISSES = 3;
    /** How many raw replies are logged in full (hex), so the robot session can place the reply CRC. */
    static final int LOGGED_REPLIES = 3;
    /** The DOA GET frame, built once; Port.write only reads it. */
    private static final byte[] DOA_QUERY = NcFrames.doaQuery();
    /** While uncalibrated, a raw value is logged at most this often, for calibration. */
    static final long RAW_LOG_PERIOD_MS = 1000;

    /** The launcher's settings for the NC chip, from the properties above. */
    public static final class Config {
        /** The confirmed port node, or "" when unset. */
        public final String port;
        /** The calibration, or null while uncalibrated. */
        public final NcFrames.Calibration calibration;

        private Config(String port, NcFrames.Calibration calibration) {
            this.port = port;
            this.calibration = calibration;
        }

        /** From the four property values ("" or null when unset). */
        public static Config of(String port, String zero, String sign, String scale) {
            return new Config(port == null ? "" : port.trim(), NcFrames.Calibration.parse(zero, sign, scale));
        }

        static final Config NONE = of("", "", "", "");
    }

    /** Where the log lines go; the launcher passes android.util.Log. Lines carry
     * counts, raw values and status codes only. */
    public interface Logger {
        void log(String msg);
    }

    /** The vendor calls the NC path makes: they only allocate and configure the port. */
    interface NcNative {
        long createUART(int buffer, String node);

        int initNCUART(long handle);
    }

    /** Our own streams on the port node. */
    interface Port {
        int available() throws IOException;

        int read(byte[] buf, int off, int len) throws IOException;

        void write(byte[] frame) throws IOException;

        void close();
    }

    interface Nodes {
        boolean exists(String path);

        Port open(String path) throws IOException;
    }

    private static final Logger QUIET = new Logger() {
        @Override
        public void log(String msg) {
        }
    };

    private static Config config = Config.NONE;
    private static Logger logger = QUIET;
    private static VoiceDirection opened;

    private volatile Backend backend;
    private final String detail;
    private final ConexantDSP cx;
    private final Logger log;
    // The NC backend's state; query() and its helpers hold this.
    private final Port port;
    private final NcFrames.Calibration calibration;
    private final long deadlineMs;
    private final byte[] reply = new byte[NcFrames.LONG_REPLY];
    private final byte[] sink = new byte[64];
    private boolean toggled;
    private int misses;
    private int logged;
    private int lastRaw = -1;
    private String firstReplyHex;
    private long rawLoggedAt;

    private VoiceDirection(Backend backend, String detail, ConexantDSP cx, Port port,
                           NcFrames.Calibration calibration, long deadlineMs, Logger log) {
        this.backend = backend;
        this.detail = detail;
        this.cx = cx;
        this.port = port;
        this.calibration = calibration;
        this.deadlineMs = deadlineMs;
        this.log = log;
    }

    private static VoiceDirection none(String why, Logger log) {
        return new VoiceDirection(Backend.NONE, why, null, null, null, 0, log);
    }

    /** Sets the NC port, the calibration and the log before the first open();
     * once open() has run this changes nothing. */
    public static synchronized void configure(Config c, Logger l) {
        if (opened == null) {
            config = c == null ? Config.NONE : c;
            logger = l == null ? QUIET : l;
        }
    }

    /** The process's one instance, opened on first use; never throws. */
    public static synchronized VoiceDirection open() {
        if (opened == null) {
            opened = tryOpen(config, logger);
            logger.log("voice direction: backend " + opened.backend + " (" + opened.detail + ")");
        }
        return opened;
    }

    private static VoiceDirection tryOpen(Config cfg, Logger log) {
        StringBuilder why = new StringBuilder();
        try {
            ConexantDSP cx = new ConexantDSP();
            int status = cx.initDSPComm();
            if (status >= 0) {
                return new VoiceDirection(Backend.CONEXANT, "conexant firmware " + cx.getDSPFirmwareVersion(),
                        cx, null, null, 0, log);
            }
            why.append("conexant init ").append(status);
        } catch (Throwable t) {
            why.append("conexant: ").append(t.getClass().getSimpleName());
        }
        return openNc(cfg, VENDOR, FILES, REPLY_DEADLINE_MS, log, why.append("; "));
    }

    /** The NC path alone on the given native layer and nodes: the host tests' entry. */
    static VoiceDirection openWith(Config cfg, NcNative natives, Nodes nodes, long deadlineMs, Logger log) {
        return openNc(cfg, natives, nodes, deadlineMs, log == null ? QUIET : log, new StringBuilder());
    }

    private static VoiceDirection openNc(Config cfg, NcNative natives, Nodes nodes, long deadlineMs, Logger log,
                                         StringBuilder why) {
        String node = cfg == null ? "" : cfg.port;
        if (node.isEmpty()) {
            return none(why.append("nc: no ").append(PORT_PROPERTY).toString(), log);
        }
        if (!nodes.exists(node)) {
            // Without the node, createUART still answers a handle and initNCUART answers 1,
            // and the next native call segfaults the whole process (seen live 2026-09-28).
            return none(why.append("nc: no ").append(node).toString(), log);
        }
        Port p = null;
        try {
            // The vendor call only configures the port; 1 is failure (the old >= 0 check read it as success).
            int status = natives.initNCUART(natives.createUART(NC_BUFFER, node));
            if (status != 0) {
                return none(why.append("nc init ").append(status).toString(), log);
            }
            p = nodes.open(node);
            VoiceDirection d = new VoiceDirection(Backend.NC, "nc on " + node + ", "
                    + (cfg.calibration == null ? "uncalibrated" : "calibrated"), null, p, cfg.calibration,
                    deadlineMs, log);
            synchronized (d) {
                if (d.query() == NcFrames.MALFORMED) {
                    p.close();
                    VoiceDirection silent = none(why.append("nc: no reply on ").append(node).toString(), log);
                    silent.firstReplyHex = d.firstReplyHex;
                    return silent;
                }
                d.misses = 0;
            }
            return d;
        } catch (Throwable t) {
            if (p != null) {
                p.close();
            }
            return none(why.append("nc: ").append(t.getClass().getSimpleName()).toString(), log);
        }
    }

    /** Which backend answered, or NONE (also once the NC backend has closed after misses). */
    public Backend backend() {
        return backend;
    }

    /** A short line for logs and the probe: the backend's firmware or port, or why nothing opened. */
    public String detail() {
        return detail;
    }

    /** The first raw NC reply in hex (lowercase, no separators), or null when none came. */
    public synchronized String firstReplyHex() {
        return firstReplyHex;
    }

    /** The last raw NC value 0 to 255 read, or -1 when none has been. */
    public synchronized int lastRaw() {
        return lastRaw;
    }

    /** The direction of arrival in degrees right now, or null when there is no
     * backend, reporting is off, the chip is uncalibrated or the read failed. */
    public Float angle() {
        float a = degrees();
        return Float.isNaN(a) ? null : Float.valueOf(a);
    }

    /** As angle(), with NaN for no angle. The NC backend's degrees are signed
     * and calibrated, within [-180, 180). */
    public float degrees() {
        try {
            switch (backend) {
                case CONEXANT:
                    return cx.getDSPRawDOA();
                case NC:
                    return NcFrames.degrees(query(), calibration);
                default:
                    return Float.NaN;
            }
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** One GET on the NC port: the raw value, OFF, or MALFORMED (a miss).
     * Never waits past the deadline. */
    private synchronized int query() {
        if (port == null || backend != Backend.NC) {
            return NcFrames.MALFORMED;
        }
        int have = 0;
        try {
            // Anything already buffered is a late reply or line noise, never this GET's answer.
            int stale;
            while ((stale = port.available()) > 0) {
                if (port.read(sink, 0, Math.min(stale, sink.length)) <= 0) {
                    break;
                }
            }
            port.write(DOA_QUERY);
            long deadline = System.nanoTime() + deadlineMs * 1000000L;
            while (true) {
                int need = NcFrames.replyLength(reply, have);
                if (have >= need) {
                    break;
                }
                int ready = port.available();
                if (ready > 0) {
                    int n = port.read(reply, have, Math.min(ready, need - have));
                    if (n > 0) {
                        have += n;
                        continue;
                    }
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            have = 0;
        }
        if (have > 0 && (firstReplyHex == null || logged < LOGGED_REPLIES)) {
            String hex = NcFrames.hex(reply, have);
            if (firstReplyHex == null) {
                firstReplyHex = hex;
            }
            if (logged < LOGGED_REPLIES) {
                logged++;
                log.log("voice direction: nc reply " + have + " bytes " + hex);
            }
        }
        int raw = NcFrames.parseDoa(reply, have);
        if (raw == NcFrames.MALFORMED) {
            miss(have);
            return raw;
        }
        misses = 0;
        if (raw == NcFrames.OFF) {
            if (!toggled) {
                // Each toggle flips the state, so at most one per open.
                toggled = true;
                try {
                    port.write(NcFrames.doaToggle());
                    log.log("voice direction: nc reporting was off, toggled once");
                } catch (IOException e) {
                    miss(0);
                }
            }
            return raw;
        }
        lastRaw = raw;
        if (calibration == null) {
            long now = System.nanoTime();
            if (rawLoggedAt == 0 || now - rawLoggedAt >= RAW_LOG_PERIOD_MS * 1000000L) {
                rawLoggedAt = now;
                log.log("voice direction: nc raw " + raw + " (uncalibrated)");
            }
        }
        return raw;
    }

    private void miss(int have) {
        misses++;
        if (misses >= MAX_MISSES && backend == Backend.NC) {
            backend = Backend.NONE;
            port.close();
            log.log("voice direction: nc closed after " + misses + " misses in a row (last " + have + " bytes)");
        }
    }

    /** The vendor library, loaded on the first NC call only. */
    private static final NcNative VENDOR = new NcNative() {
        private NCDsp nc;

        private NCDsp nc() {
            if (nc == null) {
                nc = new NCDsp();
            }
            return nc;
        }

        @Override
        public long createUART(int buffer, String node) {
            return nc().createUART(buffer, node);
        }

        @Override
        public int initNCUART(long handle) {
            return nc().initNCUART(handle);
        }
    };

    /** The real node: File.exists, then our own streams on it. */
    private static final Nodes FILES = new Nodes() {
        @Override
        public boolean exists(String path) {
            return new File(path).exists();
        }

        @Override
        public Port open(String path) throws IOException {
            final FileInputStream in = new FileInputStream(path);
            final FileOutputStream out;
            try {
                out = new FileOutputStream(path);
            } catch (IOException e) {
                in.close();
                throw e;
            }
            return new Port() {
                @Override
                public int available() throws IOException {
                    return in.available();
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    return in.read(buf, off, len);
                }

                @Override
                public void write(byte[] frame) throws IOException {
                    out.write(frame);
                    out.flush();
                }

                @Override
                public void close() {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // closing the other one anyway
                    }
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // nothing more to do
                    }
                }
            };
        }
    };

    /** Starts reading angle() every periodMs on a daemon thread, keeping the
     * readings until drained. The caller stops it. */
    public Sampler sample(long periodMs) {
        return new Sampler(this, Math.max(1, periodMs));
    }

    /** Like open().sample(periodMs), but the first open() runs on the sampler's own
     * thread, so a caller holding a lock (the ears capture thread) never waits on a tty
     * open or write that blocks (review P1, 2026-09-29). drain() is empty until it opens. */
    public static Sampler sampleLazily(long periodMs) {
        return sampleLazily(periodMs, new Callable<VoiceDirection>() {
            @Override
            public VoiceDirection call() {
                return open();
            }
        });
    }

    static Sampler sampleLazily(long periodMs, Callable<VoiceDirection> opener) {
        return new Sampler(opener, Math.max(1, periodMs));
    }

    /** The median of the non-null values, or null with none. */
    public static Float median(List<Float> values) {
        List<Float> sorted = new ArrayList<Float>();
        for (Float v : values) {
            if (v != null && !v.isNaN()) {
                sorted.add(v);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** The one daemon thread every Sampler reads on; an ears session starts a
     * Sampler per utterance, so each one must not cost a thread. */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "voice-direction");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** A running angle sampler; readings that failed are kept as null so a
     * drain says how often the backend answered. */
    public static final class Sampler {
        /** Readings kept between drains; a stuck caller never grows this without bound. */
        static final int CAP = 600;

        /** Opened on the scheduler thread by the first tick when the sampler was started lazily. */
        private final Callable<VoiceDirection> opener;
        private VoiceDirection source;
        private final List<Float> samples = new ArrayList<Float>();
        private final ScheduledFuture<?> ticks;

        Sampler(VoiceDirection source, long periodMs) {
            this(source, null, periodMs);
        }

        Sampler(Callable<VoiceDirection> opener, long periodMs) {
            this(null, opener, periodMs);
        }

        private Sampler(VoiceDirection source, Callable<VoiceDirection> opener, long periodMs) {
            this.source = source;
            this.opener = opener;
            ticks = SCHEDULER.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    sampleOnce();
                }
            }, 0, periodMs, TimeUnit.MILLISECONDS);
        }

        /** Scheduler thread only. */
        private void sampleOnce() {
            if (source == null) {
                try {
                    source = opener.call();
                } catch (Exception e) {
                    return;
                }
            }
            Float a = source.angle();
            synchronized (samples) {
                if (samples.size() < CAP) {
                    samples.add(a);
                }
            }
        }

        /** The readings since the last drain (null for a failed read), oldest first. */
        public List<Float> drain() {
            synchronized (samples) {
                List<Float> out = new ArrayList<Float>(samples);
                samples.clear();
                return out;
            }
        }

        /** Stops the readings; one already under way finishes and is kept for the next drain. */
        public void stop() {
            ticks.cancel(false);
        }
    }
}
