package com.miko3.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;

/**
 * A settings page's anti-forgery token (voice KTD10, settings KTD6). Each GET
 * of the page mints a fresh random value and remembers it here; a POST is
 * honored only when it carries one of the remembered values. A cross-site
 * form on some other page can't read our page, so it can't learn a token,
 * so it can't change settings or exit the mode.
 *
 * The capacity is how many of the most recently issued tokens still pass.
 * Voice uses 1: only the latest token passes, so a page that has since been
 * re-rendered (a reload, or another browser opening the page) must be
 * reloaded before it can save. The launcher uses a few, because the robot's
 * own WebView may sit on its settings page and would otherwise invalidate a
 * LAN browser's form every time it re-renders.
 *
 * Deliberately stricter than the remote-control mode's page token, which
 * accepts any non-empty value.
 *
 * Plain Java (no android.*) so scripts/tests can exercise it on the host JVM.
 */
public final class PageToken {
    private final SecureRandom random = new SecureRandom();
    private final int capacity;
    /** Newest first; never longer than capacity. Guarded by this. */
    private final ArrayDeque<String> issued = new ArrayDeque<>();

    /** @param capacity how many of the latest tokens check() accepts; at least 1. */
    public PageToken(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Mints, remembers, and returns a new token (128 random bits, hex). */
    public synchronized String issue() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        String token = hex.toString();
        issued.addFirst(token);
        while (issued.size() > capacity) {
            issued.removeLast();
        }
        return token;
    }

    /**
     * True only for a value one of the last capacity issue() calls returned.
     * Each comparison is constant-time, and every remembered token is compared.
     */
    public synchronized boolean check(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] given = presented.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (String token : issued) {
            match |= MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given);
        }
        return match;
    }
}
