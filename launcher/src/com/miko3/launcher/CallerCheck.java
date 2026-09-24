package com.miko3.launcher;

import com.miko3.shared.ClaudeAccess;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who may read the Claude settings through RobotSettingsService (settings
 * plan U5, KTD1; R13, R14), and what they get. Plain Java: the service does
 * the Android lookups (calling uid, its packages, their signing certificates)
 * and hands the results here, so host tests can run the decision itself.
 *
 * A caller is allowed only when one of its uid's packages is a Miko 3 app
 * and that package is signed by exactly the certificate pinned for it. A
 * signature permission can't express this, because every one of our APKs has
 * its own signing key. This stops the other apps already on the robot, such
 * as the vendor's; it can't stop a sideloaded impostor, since the committed
 * keystores and their passwords are public, but sideloading needs root adb,
 * which can read the key anyway.
 */
final class CallerCheck {
    /** One package's signing-certificate SHA-256 digests (hex), or null if
     * the package isn't installed. */
    interface Signers {
        List<String> digestsOf(String packageName);
    }

    /**
     * Package name to the SHA-256 of the certificate its committed keystore
     * signs with (lower-case hex). To regenerate one, with the alias and
     * password from its scripts/build-*.py (both equal the alias):
     *
     *   keytool -exportcert -keystore launcher/miko3-launcher.keystore \
     *       -alias miko3launcher -storepass miko3launcher -file cert.der
     *   openssl dgst -sha256 cert.der
     *
     * and cross-check a built APK with `apksigner verify --print-certs`.
     * scripts/tests/test_robot_settings_service.py recomputes all four.
     */
    static final Map<String, String> PINS;

    static {
        Map<String, String> pins = new HashMap<String, String>();
        pins.put("com.miko3.launcher", "ed2d1904659bad4e0e76d953b67086a8e31bf388fb4a6788a7a8aa5352e8c860");
        pins.put("com.miko3.mode.voice", "403f908efef0cae7f5cad2e8dd7b7d3699ae4a28c1b2c5c53b0b1541f5a0c9c5");
        pins.put("com.miko3.mode.explore", "b72f46e18b82c81bef1be49c40328fd5b755e3716ac4cedfdbb4bb4c2bc25b4a");
        pins.put("com.miko3.mode.remotecontrol", "4aaeef73bf73cf33fbd71b56fc5e33330aa5bf0f3d4e88f208a97c6f09f91862");
        PINS = Collections.unmodifiableMap(pins);
    }

    private CallerCheck() {
    }

    /** True when some package in packages is a Miko 3 app signed only by its
     * pinned certificate. No packages (an unknown uid) means deny. */
    static boolean allows(String[] packages, Signers signers) {
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            String pinned = pkg == null ? null : PINS.get(pkg);
            if (pinned != null && signedOnlyBy(signers.digestsOf(pkg), pinned)) {
                return true;
            }
        }
        return false;
    }

    private static boolean signedOnlyBy(List<String> digests, String pinned) {
        if (digests == null || digests.isEmpty()) {
            return false;
        }
        for (String d : digests) {
            if (d == null || !pinned.equals(d.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** The answer for an allowed caller: the stored values, or "not set up
     * yet" before setup and after Forget key (R7, R16). */
    static ClaudeAccess accessFor(ClaudeSettings.Credentials c) {
        return ClaudeAccess.setUp(c.baseUrl, c.apiKey, c.model);
    }
}
