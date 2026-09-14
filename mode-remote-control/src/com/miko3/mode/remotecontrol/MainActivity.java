package com.miko3.mode.remotecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Full-screen WebView shell hosting the mode's own served page (R11).
 * Launched by the launcher via explicit Intent, not HOME. Requests the
 * CAMERA/RECORD_AUDIO runtime permissions U6/U8's capture code needs,
 * matching how launcher/MainActivity.java already requests
 * ACCESS_FINE_LOCATION — a denial is reported, not silently retried.
 *
 * No lambdas/method references, matching the rest of this repo — see
 * launcher/README.md for why.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ModeMainActivity";
    private static final int REQ_PERMISSIONS = 2001;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logWebViewCapability();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        setContentView(webView);

        requestRuntimePermissionsThenLoad();
    }

    private void logWebViewCapability() {
        PackageInfo wv = WebView.getCurrentWebViewPackage();
        if (wv != null) {
            Log.i(TAG, "WebView provider: " + wv.packageName + " versionName=" + wv.versionName);
        } else {
            Log.w(TAG, "WebView.getCurrentWebViewPackage() returned null");
        }
    }

    private void requestRuntimePermissionsThenLoad() {
        boolean needCamera = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        if (needCamera || needAudio) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_PERMISSIONS);
        }
        // Load regardless of the outcome — a denial is reported by U6/U8's own
        // capture-error path when their capture actually fails to start, matching
        // how U6's camera-open-failure handling works, rather than blocking the
        // whole page on a permission the operator might grant later from Settings.
        webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                Log.i(TAG, permissions[i] + " granted=" + (grantResults[i] == PackageManager.PERMISSION_GRANTED));
            }
        }
    }
}
