package com.miko3.mode.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The settings page's anti-forgery token (KTD10). Each GET of the page
 * mints a fresh random value and remembers it here as the one issued
 * value; the settings POST and /exit are honored only when they carry
 * exactly that value. A cross-site form on some other page can't read our
 * page, so it can't learn the token, so it can't redirect the microphone
 * or exit the mode.
 *
 * Deliberately stricter than the remote-control mode's page token, which
 * accepts any non-empty value: here only the most recently issued token
 * passes, so a page that has since been re-rendered (a reload, or another
 * browser opening the page) must be reloaded before it can save. The
 * on-device WebView shows the device view, never this page, so it never
 * invalidates a LAN browser's token.
 *
 * Plain Java (no android.*) so scripts/tests can exercise it on the host JVM.
 */
final class PageToken {
    private final SecureRandom random = new SecureRandom();
    private volatile String issued;

    /** Mints, remembers, and returns a new token (128 random bits, hex). */
    synchronized String issue() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        issued = hex.toString();
        return issued;
    }

    /** True only for the value the latest issue() returned. Constant-time compare. */
    boolean check(String presented) {
        String current = issued;
        if (current == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(current.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
