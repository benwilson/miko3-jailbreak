package com.miko3.shared;

import com.example.conexantapi.ConexantDSP;

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
 * unset, or naming a node that does not exist, nothing touches a port. No
 * vendor code runs on the NC path: its calls segfaulted the launcher, first on a
 * missing UART and then seconds after every open of the real one
 * (docs/solutions/runtime-errors/vendor-dsp-direction-open-segfaults-launcher-miko3.md).
 *
 * Verified on the robot (2026-09-29): the chip streams one direction frame a
 * second by itself (NcFrames), so the NC backend only reads; it never writes to
 * the port. stty sets the port to 115200 8N1 with echo and line discipline off,
 * and because toybox 0.7.6 stty cannot clear icrnl/ixon/ixoff/inpck by flag (and
 * its `raw` sets them) the input flags are zeroed by writing back the -g string
 * with its first field set to 0, then checked. open() waits up to FIRST_FRAME_MS
 * for the first direction frame; each read drains what is buffered without
 * blocking, keeps the newest raw value and when it came, and a reading older
 * than FRESH_MS gives no angle. A read error closes the NC backend for the life
 * of the process. The raw value becomes signed degrees only once the calibration
 * properties are set (KTD12); until then the angle is NaN.
 *
 * The angle is meant to be sampled at a caller-set cadence on its own thread
 * (sample()), never from the capture thread, and reduced to a median over an
 * utterance rather than read once at its endpoint. Nothing here sets DSP modes
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
    /** How long each stty run may take. */
    static final long STTY_TIMEOUT_MS = 2000;
    /** How long open() waits for the first direction frame (the chip sends one a second). */
    static final long FIRST_FRAME_MS = 1500;
    /** How old the newest reading may be and still give an angle. */
    static final long FRESH_MS = 1500;
    /** How often open() looks for the first frame. */
    static final long POLL_MS = 10;
    /** The rolling read buffer; the parse leaves at most one torn frame in it between reads. */
    static final int BUFFER_BYTES = 512;
    /** How many direction frames are logged in full (hex). */
    static final int LOGGED_FRAMES = 3;
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

    /** Sets the port to 115200 8N1 with echo off and input flags 0; 0 is success. No vendor
     * code: with the vendor's createUART/initNCUART in the process the launcher segfaulted
     * seconds after every chip open on the robot (2026-09-29). */
    interface PortSetup {
        int configure(String node);
    }

    /** Our own streams on the port node. The NC backend only reads (the chip streams). */
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
    // The NC backend's state; drain() and its callers hold this.
    private final Port port;
    private final NcFrames.Calibration calibration;
    private final long freshNs;
    private final byte[] buf = new byte[BUFFER_BYTES];
    private int have;
    private int logged;
    private int lastRaw = -1;
    private long lastRawAt;
    private String firstReplyHex;
    private long rawLoggedAt;
    private final NcFrames.Sink frames = new NcFrames.Sink() {
        @Override
        public void direction(byte[] b, int at, int raw) {
            onFrame(b, at, raw);
        }
    };

    private VoiceDirection(Backend backend, String detail, ConexantDSP cx, Port port,
                           NcFrames.Calibration calibration, long freshMs, Logger log) {
        this.backend = backend;
        this.detail = detail;
        this.cx = cx;
        this.port = port;
        this.calibration = calibration;
        this.freshNs = freshMs * 1000000L;
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
        return openNc(cfg, STTY, FILES, FIRST_FRAME_MS, FRESH_MS, log, why.append("; "));
    }

    /** The NC path alone on the given port setup and nodes: the host tests' entry. */
    static VoiceDirection openWith(Config cfg, PortSetup setup, Nodes nodes, long firstFrameMs, long freshMs,
                                   Logger log) {
        return openNc(cfg, setup, nodes, firstFrameMs, freshMs, log == null ? QUIET : log, new StringBuilder());
    }

    private static VoiceDirection openNc(Config cfg, PortSetup setup, Nodes nodes, long firstFrameMs, long freshMs,
                                         Logger log, StringBuilder why) {
        String node = cfg == null ? "" : cfg.port;
        if (node.isEmpty()) {
            return none(why.append("nc: no ").append(PORT_PROPERTY).toString(), log);
        }
        if (!nodes.exists(node)) {
            // A property naming a missing node (the old /dev/ttyMT2 case) opens nothing.
            return none(why.append("nc: no ").append(node).toString(), log);
        }
        Port p = null;
        try {
            int status = setup.configure(node);
            if (status != 0) {
                return none(why.append("nc stty ").append(status).append(sttyStep(status)).toString(), log);
            }
            p = nodes.open(node);
            VoiceDirection d = new VoiceDirection(Backend.NC, "nc on " + node + ", "
                    + (cfg.calibration == null ? "uncalibrated" : "calibrated"), null, p, cfg.calibration,
                    freshMs, log);
            // The chip streams a frame a second: wait for the first one without blocking.
            long deadline = System.nanoTime() + firstFrameMs * 1000000L;
            while (true) {
                synchronized (d) {
                    d.drain();
                    if (d.lastRaw >= 0) {
                        return d;
                    }
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
                Thread.sleep(POLL_MS);
            }
            p.close();
            return none(why.append("nc: no frames on ").append(node).toString(), log);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (p != null) {
                p.close();
            }
            return none(why.append("nc: ").append(t.getClass().getSimpleName()).toString(), log);
        }
    }

    /** Which backend answered, or NONE (also once the NC backend has closed on a read error). */
    public Backend backend() {
        return backend;
    }

    /** A short line for logs and the probe: the backend's firmware or port, or why nothing opened. */
    public String detail() {
        return detail;
    }

    /** The first NC direction frame in hex (lowercase, no separators), or null when none came. */
    public synchronized String firstReplyHex() {
        return firstReplyHex;
    }

    /** The newest raw NC value 0 to 255 read, or -1 when none has been. */
    public synchronized int lastRaw() {
        return lastRaw;
    }

    /** The direction of arrival in degrees right now, or null when there is no
     * backend, no fresh reading, the chip is uncalibrated or the read failed. */
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
                    return NcFrames.degrees(freshRaw(), calibration);
                default:
                    return Float.NaN;
            }
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** The newest raw value if it is at most FRESH_MS old, else -1. Reads only. */
    private synchronized int freshRaw() {
        if (port == null || backend != Backend.NC) {
            return -1;
        }
        try {
            drain();
        } catch (IOException e) {
            backend = Backend.NONE;
            port.close();
            log.log("voice direction: nc read failed (" + e.getClass().getSimpleName() + "), closed");
            return -1;
        }
        if (lastRaw < 0 || System.nanoTime() - lastRawAt > freshNs) {
            return -1;
        }
        return lastRaw;
    }

    /** Reads everything buffered on the port, never blocking, and parses every frame in it. */
    private void drain() throws IOException {
        int ready;
        while ((ready = port.available()) > 0) {
            int n = port.read(buf, have, Math.min(ready, buf.length - have));
            if (n <= 0) {
                break;
            }
            have += n;
            int used = NcFrames.parseStream(buf, have, frames);
            // What is left is shorter than a frame: a frame still arriving, kept for the next read.
            System.arraycopy(buf, used, buf, 0, have - used);
            have -= used;
        }
    }

    private void onFrame(byte[] b, int at, int raw) {
        if (firstReplyHex == null || logged < LOGGED_FRAMES) {
            String hex = NcFrames.hex(b, at, NcFrames.FRAME_LENGTH);
            if (firstReplyHex == null) {
                firstReplyHex = hex;
            }
            if (logged < LOGGED_FRAMES) {
                logged++;
                log.log("voice direction: nc frame " + hex);
            }
        }
        lastRaw = raw;
        long now = System.nanoTime();
        lastRawAt = now;
        if (calibration == null && (rawLoggedAt == 0 || now - rawLoggedAt >= RAW_LOG_PERIOD_MS * 1000000L)) {
            rawLoggedAt = now;
            log.log("voice direction: nc raw " + raw + " (uncalibrated)");
        }
    }

    /** stty step 1: 115200 8N1, no modem control, no output processing, echo and line
     * discipline off. Not `raw`: toybox 0.7.6's raw sets icrnl/ixon/ixoff/inpck. */
    static List<String> sttyCommand(String node) {
        return java.util.Arrays.asList("stty", "-F", node, "115200", "cs8", "-cstopb", "-parenb", "clocal",
                "-crtscts", "-hupcl", "-opost", "-isig", "-icanon", "-iexten", "-echo", "-echoe", "-echok",
                "-echonl");
    }

    /** stty steps 2 and 4: print the settings as one colon-separated -g string. */
    static List<String> sttyReadCommand(String node) {
        return java.util.Arrays.asList("stty", "-F", node, "-g");
    }

    /** stty step 3: write a -g string back, as one argument. */
    static List<String> sttyWriteCommand(String node, String g) {
        return java.util.Arrays.asList("stty", "-F", node, g);
    }

    /** The -g string with its first field (the input flags) set to 0, or null when it is
     * malformed (fewer than 4 fields, or a field that is not hex). */
    static String zeroInputFlags(String g) {
        if (g == null) {
            return null;
        }
        String[] fields = g.trim().split(":", -1);
        if (fields.length < 4) {
            return null;
        }
        for (String f : fields) {
            if (!f.matches("[0-9a-fA-F]+")) {
                return null;
            }
        }
        StringBuilder sb = new StringBuilder("0");
        for (int i = 1; i < fields.length; i++) {
            sb.append(':').append(fields[i]);
        }
        return sb.toString();
    }

    /** Whether a well-formed -g string has input flags 0. */
    static boolean inputFlagsZero(String g) {
        return zeroInputFlags(g) != null && g.trim().startsWith("0:");
    }

    /** The real setup's status: STEP * step number + the step's code. */
    static final int STEP = 1000;
    static final int STEP_FLAGS = 1;
    static final int STEP_READ = 2;
    static final int STEP_WRITE = 3;
    static final int STEP_VERIFY = 4;
    /** Step codes past any exit value: stty timed out, failed to start, was interrupted,
     * printed a malformed -g string, or left the input flags set. */
    static final int TIMED_OUT = 997;
    static final int NO_PROCESS = 998;
    static final int INTERRUPTED = 999;
    static final int MALFORMED_G = 996;
    static final int FLAGS_SET = 995;

    /** " at <step>" for a real setup status, "" for any other. */
    static String sttyStep(int status) {
        switch (status / STEP) {
            case STEP_FLAGS:
                return " at set flags";
            case STEP_READ:
                return " at read -g";
            case STEP_WRITE:
                return " at write -g";
            case STEP_VERIFY:
                return " at verify -g";
            default:
                return "";
        }
    }

    /** One stty run: its exit value (or a code above), and its output in out[0]. */
    private static int stty(List<String> cmd, String[] out) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(STTY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return TIMED_OUT;
            }
            if (out != null) {
                // -g prints one line.
                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "US-ASCII"));
                try {
                    out[0] = in.readLine();
                } finally {
                    in.close();
                }
            }
            return p.exitValue();
        } catch (IOException e) {
            return NO_PROCESS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return INTERRUPTED;
        }
    }

    /** The real port setup: the flags, then input flags 0 through -g, then a check. */
    private static final PortSetup STTY = new PortSetup() {
        @Override
        public int configure(String node) {
            int status = stty(sttyCommand(node), null);
            if (status != 0) {
                return STEP * STEP_FLAGS + status;
            }
            String[] g = new String[1];
            status = stty(sttyReadCommand(node), g);
            if (status != 0) {
                return STEP * STEP_READ + status;
            }
            String zeroed = zeroInputFlags(g[0]);
            if (zeroed == null) {
                return STEP * STEP_READ + MALFORMED_G;
            }
            status = stty(sttyWriteCommand(node, zeroed), null);
            if (status != 0) {
                return STEP * STEP_WRITE + status;
            }
            status = stty(sttyReadCommand(node), g);
            if (status != 0) {
                return STEP * STEP_VERIFY + status;
            }
            return inputFlagsZero(g[0]) ? 0 : STEP * STEP_VERIFY + FLAGS_SET;
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
