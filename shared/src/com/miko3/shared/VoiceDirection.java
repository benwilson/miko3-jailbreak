package com.miko3.shared;

import com.example.conexantapi.ConexantDSP;
import com.example.conexantapi.NCDsp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The direction a voice comes from, read in our own process from the vendor's
 * DSP library (meeting plan U1, KTD4; docs/hardware/voice-mic.md section 3).
 * A thin facade over the two JNI stubs under com.example.conexantapi: open()
 * tries the Conexant backend and then the NC one, remembers which answered,
 * and angle() reads the direction of arrival in degrees from it, or null when
 * neither backend is there or the read fails. The vendor chooses its backend
 * from the motor controller's loopback boot marker (<LOOPBACK_TEST_BYTE> for
 * Conexant, <LOOPBACK_TEST_BYTE_GD> for NC); our driver never runs that boot
 * exchange, so this asks each backend instead and reports the one that
 * answered.
 *
 * The angle is meant to be sampled at a caller-set cadence on its own thread
 * (sample()), never from the capture thread, and reduced to a median over an
 * utterance rather than read once at its endpoint. Nothing here sets DSP
 * modes or gains: the chip stays as the platform left it, and the owner's
 * measurement session (U2) says whether the angle is usable.
 *
 * Plain Java with no android.* imports. On a host JVM the stubs' static
 * initialisers fail to load the library; open() catches that and reports
 * Backend.NONE, so host tests can run against it.
 */
public final class VoiceDirection {
    public enum Backend { NONE, CONEXANT, NC }

    /** The library the stubs load, staged by scripts/build-custom-launcher.py. */
    public static final String LIBRARY = "conexant_dsp_lib";
    /** The NC backend's UART node and buffer, as the vendor opens it (DSPSettings.openNCUART). */
    static final String NC_UART = "/dev/ttyMT2";
    static final int NC_BUFFER = 500;

    private static VoiceDirection opened;

    private final Backend backend;
    private final String detail;
    private final ConexantDSP cx;
    private final NCDsp nc;
    private final long ncHandle;

    private VoiceDirection(Backend backend, String detail, ConexantDSP cx, NCDsp nc, long ncHandle) {
        this.backend = backend;
        this.detail = detail;
        this.cx = cx;
        this.nc = nc;
        this.ncHandle = ncHandle;
    }

    /** The process's one instance, opened on first use; never throws. */
    public static synchronized VoiceDirection open() {
        if (opened == null) {
            opened = tryOpen();
        }
        return opened;
    }

    private static VoiceDirection tryOpen() {
        StringBuilder why = new StringBuilder();
        try {
            ConexantDSP cx = new ConexantDSP();
            int status = cx.initDSPComm();
            if (status >= 0) {
                return new VoiceDirection(Backend.CONEXANT, "conexant firmware " + cx.getDSPFirmwareVersion(),
                        cx, null, 0);
            }
            why.append("conexant init ").append(status);
        } catch (Throwable t) {
            why.append("conexant: ").append(t.getClass().getSimpleName());
        }
        if (!new File(NC_UART).exists()) {
            // Without the node, createUART still answers a handle and initNCUART answers 1,
            // and the next native call segfaults the whole process (seen live 2026-09-28).
            why.append("; nc: no ").append(NC_UART);
            return new VoiceDirection(Backend.NONE, why.toString(), null, null, 0);
        }
        try {
            NCDsp nc = new NCDsp();
            long handle = nc.createUART(NC_BUFFER, NC_UART);
            int status = nc.initNCUART(handle);
            if (status >= 0) {
                if (nc.getCurrentDOAStatus(handle) == -1) {
                    nc.toggleDOA(handle);
                }
                return new VoiceDirection(Backend.NC, "nc firmware " + nc.getFWVersion(handle), null, nc, handle);
            }
            why.append("; nc init ").append(status);
        } catch (Throwable t) {
            why.append("; nc: ").append(t.getClass().getSimpleName());
        }
        return new VoiceDirection(Backend.NONE, why.toString(), null, null, 0);
    }

    /** Which backend answered, or NONE. */
    public Backend backend() {
        return backend;
    }

    /** A short line for logs and the probe: the firmware version, or why nothing opened. */
    public String detail() {
        return detail;
    }

    /** The direction of arrival in degrees right now, or null when there is no
     * backend, reporting is off (NC answers -1) or the read failed. */
    public Float angle() {
        try {
            switch (backend) {
                case CONEXANT: {
                    float a = cx.getDSPRawDOA();
                    return Float.isNaN(a) ? null : Float.valueOf(a);
                }
                case NC: {
                    int a = nc.getCurrentDOAStatus(ncHandle);
                    return a == -1 ? null : Float.valueOf(a);
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts reading angle() every periodMs on a daemon thread, keeping the
     * readings until drained. The caller stops it. */
    public Sampler sample(long periodMs) {
        return new Sampler(this, Math.max(1, periodMs));
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

        private final VoiceDirection source;
        private final List<Float> samples = new ArrayList<Float>();
        private final ScheduledFuture<?> ticks;

        Sampler(VoiceDirection source, long periodMs) {
            this.source = source;
            ticks = SCHEDULER.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    sampleOnce();
                }
            }, 0, periodMs, TimeUnit.MILLISECONDS);
        }

        private void sampleOnce() {
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
