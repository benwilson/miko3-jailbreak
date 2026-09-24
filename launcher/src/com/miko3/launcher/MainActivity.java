package com.miko3.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * The home screen itself (U9): a full-screen WebView pointed at the
 * launcher's own served page (device info + Wi-Fi, R8/R11) — replacing
 * the original native-Views UI so the launcher's screens render through
 * the same HTML/Pico.css approach as modes, on-device and remotely
 * (R9/R10) alike.
 *
 * No lambdas/method references anywhere in this file on purpose: it's
 * compiled with -bootclasspath set to the Android platform jar only (see
 * scripts/build-custom-launcher.py), which has no java.lang.invoke.
 * LambdaMetafactory for javac to target, so lambda syntax fails to compile.
 * Anonymous inner classes are the equivalent here.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1001;

    private WebView webView;
    private WifiManager wifiManager;

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Only the home page shows Wi-Fi state (KTD9). Reloading the Settings
            // page would wipe a half-typed key and issue a fresh page token.
            if (SettingsPage.isHomePageUrl(webView.getUrl())) {
                webView.reload();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Without this, the http://127.0.0.1:PORT load below (which redirects to
        // https://127.0.0.1:HTTPS_PORT once RoutingHttpServer's HTTPS listener is up)
        // hits the default SSL-error handling, which treats our self-signed
        // certificate as fatal and silently aborts the load — confirmed live as the
        // same root cause behind mode-remote-control/MainActivity's identical fix.
        // Safe to proceed unconditionally: this WebView only ever talks to our own
        // server on the loopback interface.
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.proceed();
            }
        });
        setContentView(webView);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiReceiver, filter);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } else {
            wifiManager.startScan();
        }

        webView.loadUrl("http://127.0.0.1:" + LauncherApp.PORT + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            wifiManager.startScan();
            webView.reload();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(wifiReceiver);
        } catch (IllegalArgumentException ignored) {
            // was never registered (e.g. destroyed before onCreate finished) — fine
        }
        super.onDestroy();
    }
}
