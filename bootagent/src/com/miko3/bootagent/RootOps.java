package com.miko3.bootagent;

import android.util.Log;

import java.io.OutputStream;

/**
 * The privileged boot payload. Empirically grounded on this unit (Android 9 / mt8167, verity
 * enforcing, SELinux Permissive, /system/bin/su setuid root with no `-c`):
 *
 *   - adbd is `disabled` in read-only init and USB-config-gated, so it is OFF at boot and the
 *     anti-tamper watchdog (ServiceExam) sees nothing during startup;
 *   - no init trigger consumes persist.adb.tcp.port, and /system + /vendor init are RO under
 *     verity, so the only way to run a root command at boot is an app boot-receiver — this class.
 *
 * Ordering matters (KTD2): neuterd must shadow /system/bin/reboot BEFORE adbd is started, or
 * ServiceExam reboots the unit. So the payload starts neuterd, waits for the shadow, and only then
 * touches the adb properties. The adb USB combo is set directly on sys.usb.config and is never
 * persisted, because /init.usb.rc applies persist.sys.usb.config at boot and would start adbd
 * before the neuter exists.
 *
 * neuterd is a freestanding arm64 daemon that setns()es into init's global mount namespace and
 * keeps the no-op shadowed there (self-healing). It is embedded below as base64, regenerated from
 * native/neuterd by build-bootagent.py. Miko's `su` has no `-c`, so the script is fed via stdin.
 */
public final class RootOps {

    private static final String TAG = "Miko3Boot";

    /** neuterd (arm64 ELF, freestanding) as base64 — regenerated from native/neuterd by build-bootagent.py. */
    private static final String NEUTERD_B64 = "@@NEUTERD_B64@@";

    /** Root shell script run at every boot. Every step logs to /data/local/tmp/miko3-boot.log. */
    private static final String PAYLOAD =
            "D=/data/local/tmp/neuterd\n" +
            "LOG=/data/local/tmp/miko3-boot.log\n" +
            "W=/data/local/tmp/miko3-usb-watch.sh\n" +
            "echo \"[boot up=$(cut -d' ' -f1 /proc/uptime)] miko3 bootagent\" >> \"$LOG\"\n" +
            // 1) materialize the self-healing global-namespace neuter daemon from embedded base64
            "echo '" + NEUTERD_B64 + "' | base64 -d > \"$D\" 2>>\"$LOG\"; chmod 755 \"$D\"\n" +
            // 2) start it BEFORE adbd, so the watchdog is defused before there is an adbd to grep
            "pkill -f /data/local/tmp/neuterd 2>/dev/null\n" +
            "setsid \"$D\" </dev/null >>\"$LOG\" 2>&1 &\n" +
            // 3) wait for the shadow to land (bounded), then report
            "i=0\n" +
            "while [ \"$i\" -lt 20 ]; do\n" +
            "  [ \"$(wc -c < /system/bin/reboot)\" -lt 100 ] && break\n" +
            "  sleep 1; i=$((i+1))\n" +
            "done\n" +
            "if [ \"$(wc -c < /system/bin/reboot)\" -lt 100 ]; then\n" +
            "  echo '  reboot NEUTERED (global ns, self-healing daemon up)' >> \"$LOG\"\n" +
            "else\n" +
            "  echo \"  !! neuter NOT applied (reboot=$(wc -c < /system/bin/reboot) bytes)\" >> \"$LOG\"\n" +
            "fi\n" +
            // 4) only now bring adb up: TCP 5555 and the USB combo (KTD2, KTD7)
            "setprop service.adb.tcp.port 5555\n" +
            "setprop sys.usb.config mtp,adb\n" +
            "echo \"  adb tcp 5555 + usb mtp,adb requested; init.svc.adbd=$(getprop init.svc.adbd)\" >> \"$LOG\"\n" +
            // 5) watcher (KTD3): re-assert the USB config and restart adbd if ServiceExam reverts it
            "cat > \"$W\" <<'WEOF'\n" +
            "#!/system/bin/sh\n" +
            "while true; do\n" +
            "  case \"$(getprop sys.usb.config)\" in\n" +
            "    *adb*) : ;;\n" +
            "    *) setprop sys.usb.config mtp,adb ;;\n" +
            "  esac\n" +
            "  [ \"$(getprop init.svc.adbd)\" = \"running\" ] || setprop ctl.restart adbd\n" +
            "  sleep 3\n" +
            "done\n" +
            "WEOF\n" +
            "chmod 755 \"$W\"\n" +
            "pkill -f /data/local/tmp/miko3-usb-watch.sh 2>/dev/null\n" +
            "setsid \"$W\" </dev/null >>\"$LOG\" 2>&1 &\n" +
            // 6) keep adb alive with the screen idle (the unit lives on its charger)
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
                throw new IllegalStateException("miko3 boot payload exited non-zero: " + rc
                        + " (see /data/local/tmp/miko3-boot.log)");
            }
        } catch (Exception e) {
            Log.e(TAG, "boot payload FAILED", e);
            throw new RuntimeException("miko3 boot payload failed", e);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
