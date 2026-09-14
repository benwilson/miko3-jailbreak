package com.miko3.shared;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Drives the robot's wheels by writing "VEL1=" motion frames directly to
 * /dev/ttyS2 — the same serial line ServiceExam's SensorModule/
 * libmiko_drivers.so JNI layer uses — bypassing ServiceExam's AIDL surface,
 * its ExpressionMsg/GameEvent(135) JSON round-trip, and the actual
 * motivation: a confirmed-from-source shared lock
 * (SocialInteraction_SpeechChat.SendData()'s own intrinsic monitor) that
 * ties every motor command to ServiceExam's speech recognition, TTS, and
 * cloud-network-call machinery. If ServiceExam is mid-conversation when a
 * drive command arrives via the AIDL path, that command blocks on the same
 * lock a network call might be holding — a real, structural cause of the
 * intermittent drive freezes RobotControlClient's AIDL path has shown.
 *
 * Frame format (100 bytes total) — verified directly against this
 * project's decompiled ServiceExam sources (tools/serviceexam_jadx), not
 * secondhand:
 * com.common_source.emotix.acattsandroidsdkdemo.MotionMsg#packbytes(),
 * com.emotix.arya.app_utils.ByteUtils#convertToBytes()/convertToBytesSigned(),
 * and com.common_source.emotix.interaction.interaction.ExpressionMsg
 * #packData1()'s "MOTION1"/"MOTION3" framing (both produce byte-identical
 * wire output for a normal, non-PID frame):
 *
 * <pre>
 *   "VEL1="        (5 ASCII bytes: 0x56 0x45 0x4C 0x31 0x3D)
 *   type=4         (3B unsigned, big-endian)   — MotionMsg's own "type", not the frame's
 *   datasize=27    (3B unsigned)                — total byte length of everything below,
 *                                                  including these first two fields; fixed
 *                                                  at 27 since this class always sends
 *                                                  exactly one frame (seqCount=1)
 *   motion_type=4  (3B unsigned)
 *   loop=1         (3B unsigned)
 *   seqCount=1     (3B unsigned)
 *   linear         (3B signed — see writeSigned3())
 *   angular        (3B signed — see writeSigned3())
 *   time           (3B unsigned, centiseconds)
 *   frame.type=1   (3B unsigned — "normal" velocity frame; MotionMsg.packbytes() also
 *                    supports a frame.type==5 "PID/config" layout this class does not
 *                    implement, since no caller in this project has ever used it)
 *   'X' (0x58) padding out to 100 bytes total
 * </pre>
 *
 * KNOWN, DISCLOSED RISK — concurrent access: /dev/ttyS2 is world-writable
 * (chmod 0666 by an OEM boot script) and, confirmed live in this project's
 * own testing, does NOT enforce exclusive-open — a second reader was able to
 * open it and successfully capture a live telemetry packet meant for
 * ServiceExam. ServiceExam keeps its own file descriptor to this device open
 * essentially the entire time it runs, and can write its own motion frames
 * independent of anything this class does (idle-mode movement, a
 * voice-triggered reaction). A raw serial line has no built-in arbitration:
 * if this class and ServiceExam happen to write at the same moment, the two
 * 100-byte frames can interleave at the byte level, corrupting whichever the
 * peripheral MCU ends up reading. This class does not attempt to solve that
 * — it's safest used only during an actively held drive session (mirroring
 * this project's existing DriveLeaseService model for the AIDL path), not
 * left open continuously alongside otherwise-normal robot operation.
 *
 * WRITE-ONLY BY DESIGN: this class never reads from the device. ServiceExam
 * continuously receives MCU telemetry (~10Hz "PI3 callback" strings —
 * POWER=/IMUAC=/IMUGY=/GLPOS=/TOFIR=/Left=/Right=) over this same line; a
 * second reader steals bytes ServiceExam needs, with no way to give them
 * back (confirmed live). Motor commands are fire-and-forget from this
 * class's side, same contract as the existing AIDL-based
 * RobotControlClient, which also never inspects the MCU's ack.
 */
public final class DirectMotorDriver {
    private static final String TAG = "DirectMotorDriver";
    private static final String DEVICE_PATH = "/dev/ttyS2";
    private static final int FRAME_SIZE = 100;

    private Process suProcess;
    private OutputStream out;

    /**
     * Opens a root shell and starts a `cat` redirecting its stdin to the UART
     * device. Everything written to this instance's stream after this point
     * goes straight to the device, byte for byte, with no shell
     * interpretation — the command line below hands the shell's own stdin
     * (which is this process's OutputStream) off to `cat` as its child's
     * stdin; from then on we're feeding `cat`'s raw-copy loop, not a shell
     * parser, so arbitrary binary content (including bytes that happen to
     * look like shell metacharacters or newlines) is safe to write.
     */
    public synchronized boolean connect() {
        try {
            suProcess = new ProcessBuilder("su").redirectErrorStream(true).start();
            out = suProcess.getOutputStream();
            out.write(("cat > " + DEVICE_PATH + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "connect() failed", e);
            disconnect();
            return false;
        }
    }

    public synchronized boolean isConnected() {
        return out != null;
    }

    /**
     * Fire-and-forget, matching RobotControlClient.drive()'s own contract —
     * see class javadoc's WRITE-ONLY note for why no ack is read back.
     */
    public synchronized void drive(int linear, int angular, int timeCentiseconds) throws IOException {
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(buildFrame(linear, angular, timeCentiseconds));
        out.flush();
    }

    /** Convenience: an explicit stop (zero linear, zero angular). */
    public void stop() throws IOException {
        drive(0, 0, 10);
    }

    public synchronized void disconnect() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        if (suProcess != null) {
            suProcess.destroy();
        }
        out = null;
        suProcess = null;
    }

    /** Package-private (not private) so a unit test can call it directly without a device. */
    static byte[] buildFrame(int linear, int angular, int timeCentiseconds) {
        byte[] frame = new byte[FRAME_SIZE];
        int i = 0;
        frame[i++] = 'V';
        frame[i++] = 'E';
        frame[i++] = 'L';
        frame[i++] = '1';
        frame[i++] = '=';
        i = writeUnsigned3(frame, i, 4);  // MotionMsg's own "type"
        i = writeUnsigned3(frame, i, 27); // datasize -- fixed: single-frame blob is always 27 bytes
        i = writeUnsigned3(frame, i, 4);  // motion_type
        i = writeUnsigned3(frame, i, 1);  // loop
        i = writeUnsigned3(frame, i, 1);  // seqCount -- this class only ever sends one frame
        i = writeSigned3(frame, i, linear);
        i = writeSigned3(frame, i, angular);
        i = writeUnsigned3(frame, i, timeCentiseconds);
        i = writeUnsigned3(frame, i, 1);  // frame.type=1 ("normal" velocity frame)
        for (; i < FRAME_SIZE; i++) {
            frame[i] = 'X'; // 0x58 padding, matching ExpressionMsg.packData1()'s own fill byte
        }
        return frame;
    }

    /** Matches com.emotix.arya.app_utils.ByteUtils#convertToBytes(int): plain 3-byte
     * big-endian, for the small non-negative values (type/datasize/loop/etc.) this
     * class ever sends. */
    private static int writeUnsigned3(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) (value & 0xFF);
        return offset + 3;
    }

    /** Matches com.emotix.arya.app_utils.ByteUtils#convertToBytesSigned(int): NOT
     * two's complement — 2 big-endian magnitude bytes followed by a trailing sign
     * byte (1=positive, 2=negative, 0=zero). Confirmed directly from that method's
     * decompiled source, including for the linear/angular magnitude range
     * (well under 32768) this class actually uses. */
    private static int writeSigned3(byte[] buf, int offset, int value) {
        int magnitude = Math.abs(value);
        buf[offset] = (byte) ((magnitude >> 8) & 0xFF);
        buf[offset + 1] = (byte) (magnitude & 0xFF);
        buf[offset + 2] = (byte) (value > 0 ? 1 : (value < 0 ? 2 : 0));
        return offset + 3;
    }
}
