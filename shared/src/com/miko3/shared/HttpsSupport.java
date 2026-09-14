package com.miko3.shared;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Loads the self-signed TLS keystore vendored at shared/assets/server.p12
 * (every app compiles its own copy of shared/, KTD2-style — same asset
 * pipeline pico.min.css already uses, see scripts/build_common.py's
 * stage_assets) into an SSLContext RoutingHttpServer can serve HTTPS with.
 *
 * Self-signed, not CA-issued: there's no way to get a browser-trusted
 * certificate for a robot on a private WiFi network with a DHCP-assigned,
 * not-publicly-resolvable IP. The certificate's SANs cover
 * miko3.local/localhost/127.0.0.1 — accessing the robot by its bare LAN IP
 * will still show a hostname-mismatch warning on top of the
 * untrusted-issuer one. Both are expected; the operator accepts the
 * warning once per browser (or adds the origin to Chrome's
 * chrome://flags/#unsafely-treat-insecure-origin-as-secure allowlist,
 * which also accepts a self-signed https:// origin, not just http://).
 * Regenerate with `keytool -genkeypair ... -storetype PKCS12` (see this
 * class's own git history for the exact invocation used) if the keystore
 * password below ever needs to change — it isn't a secret worth protecting
 * (the whole point is every viewer's browser can see the cert), just a
 * required KeyStore API parameter.
 */
public final class HttpsSupport {
    private static final String TAG = "HttpsSupport";
    private static final String ASSET_NAME = "server.p12";
    private static final char[] KEYSTORE_PASSWORD = "miko3https".toCharArray();

    private HttpsSupport() {
    }

    /** Returns null (caller falls back to plain HTTP) if the keystore can't be loaded. */
    public static SSLContext loadServerContext(Context ctx) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            InputStream in = ctx.getAssets().open(ASSET_NAME);
            try {
                keyStore.load(in, KEYSTORE_PASSWORD);
            } finally {
                in.close();
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            Log.e(TAG, "failed to load HTTPS server certificate — falling back to plain HTTP", e);
            return null;
        }
    }
}
