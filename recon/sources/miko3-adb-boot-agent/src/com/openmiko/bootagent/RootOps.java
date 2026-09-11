package com.openmiko.bootagent;

import android.util.Log;

import java.io.OutputStream;

/**
 * The privileged boot payload. Empirically grounded on this unit (Android 9 / mt8167, verity
 * enforcing, SELinux Permissive):
 *   - adbd is `disabled` in read-only init and only USB-config-gated, so it is OFF at boot and the
 *     anti-tamper watchdog sees nothing during startup;
 *   - no init trigger consumes persist.adb.tcp.port and /system+/vendor init are RO under verity,
 *     so the ONLY way to run a root command at boot is an app boot-receiver — this class.
 *
 * WATCHDOG NEUTER — the hard part, done right. ServiceExam's SecurityMonitor greps `ps` for 'adbd'
 * every ~2s and, if present, execs `su -> reboot`. Shadowing /system/bin/reboot with a no-op
 * defuses it — BUT a bind-mount made from an APP process lands in the app's mount namespace, which
 * ServiceExam (a zygote child) CANNOT see, so an app-side neuter is useless. So we ship a tiny
 * native daemon `neuterd` (arm64, freestanding, no libc) that setns()'s into init's GLOBAL mount
 * namespace and keeps /system/bin/reboot shadowed, re-applying automatically if it is ever wiped
 * (self-healing). The daemon is embedded here as base64 (regenerated from native/neuterd by
 * build.sh), decoded to /data/local/tmp, and launched via setsid so it outlives this receiver.
 * adbd is enabled only AFTER the neuter is up. Miko's `su` has no `-c`, so the script is fed via
 * stdin.
 */
public final class RootOps {

    private static final String TAG = "OpenMikoBoot";

    /** neuterd (arm64 ELF, freestanding) as base64 — regenerated from native/neuterd by build.sh. */
    private static final String NEUTERD_B64 = "@@NEUTERD_B64@@";

    /** Root shell script run at every boot. Every step logs to /data/local/tmp/openmiko-boot.log. */
    private static final String PAYLOAD =
            "D=/data/local/tmp/neuterd\n" +
            "LOG=/data/local/tmp/openmiko-boot.log\n" +
            "echo \"[boot up=$(cut -d' ' -f1 /proc/uptime)] openmiko-bootagent\" >> \"$LOG\"\n" +
            // 1) materialize the self-healing global-namespace neuter daemon from embedded base64
            "echo '" + NEUTERD_B64 + "' | base64 -d > \"$D\" 2>>\"$LOG\"; chmod 755 \"$D\"\n" +
            // 2) start it BEFORE adbd, so the watchdog is defused before there is an adbd to grep
            "pkill -f /data/local/tmp/neuterd 2>/dev/null\n" +
            "setsid \"$D\" </dev/null >>\"$LOG\" 2>&1 &\n" +
            "sleep 1\n" +
            "if [ \"$(wc -c < /system/bin/reboot)\" -lt 100 ]; then " +
            "echo '  reboot NEUTERED (global ns, self-healing daemon up)' >> \"$LOG\"; " +
            "else echo \"  !! neuter NOT applied yet (reboot=$(wc -c < /system/bin/reboot) bytes)\" >> \"$LOG\"; fi\n" +
            // 3) bring adb up over tcp 5555 (only now that reboot is defused)
            "setprop service.adb.tcp.port 5555\n" +
            "setprop ctl.restart adbd\n" +
            "echo \"  adb tcp 5555 requested; init.svc.adbd=$(getprop init.svc.adbd)\" >> \"$LOG\"\n" +
            // 4) keep adb alive with the screen idle (the unit lives on its charger)
            "settings put global stay_on_while_plugged_in 3 2>>\"$LOG\" || echo '  !! stay_on set failed' >> \"$LOG\"\n" +
            "echo '  done.' >> \"$LOG\"\n";

    private RootOps() { }

    /** Feed {@link #PAYLOAD} to root via /system/bin/su. Surfaces failures loudly; never swallows. */
    public static void runBootPayload() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("/system/bin/su");
            OutputStream os = p.getOutputStream();
            os.write(PAYLOAD.getBytes("UTF-8"));
            os.flush();
            os.close();
            int rc = p.waitFor();
            Log.i(TAG, "boot payload exit=" + rc);
            if (rc != 0) {
                throw new IllegalStateException("openmiko boot payload exited non-zero: " + rc
                        + " (see /data/local/tmp/openmiko-boot.log)");
            }
        } catch (Exception e) {
            // Do NOT swallow: log at error, and re-raise so the caller/logcat sees the degraded state.
            Log.e(TAG, "boot payload FAILED", e);
            throw new RuntimeException("openmiko boot payload failed", e);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
