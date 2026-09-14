package com.miko3.shared;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Small helpers shared by every app's HTTP route handlers. */
public final class HttpUtil {
    private HttpUtil() {
    }

    public static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Reads a whole asset file into memory. Callers serving the same asset
     * repeatedly (e.g. a route handler) should cache the result themselves —
     * this does the one-time read only. */
    public static byte[] readAssetBytes(Context ctx, String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
