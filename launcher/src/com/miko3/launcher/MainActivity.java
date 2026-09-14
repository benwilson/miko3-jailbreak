package com.miko3.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The home screen itself: basic device info at the top (mirrors the web
 * page InfoHttpServer serves), plus a minimal Wi-Fi connect/disconnect/
 * forget UI, since the stock kiosk has no reachable Settings screen and the
 * device may have no way onto a network otherwise.
 *
 * No lambdas/method references anywhere in this file on purpose: it's
 * compiled with -bootclasspath set to the Android platform jar only (see
 * scripts/build-custom-launcher.py), which has no java.lang.invoke.
 * LambdaMetafactory for javac to target, so lambda syntax fails to compile.
 * Anonymous inner classes are the equivalent here.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1001;

    private WifiManager wifiManager;
    private TextView infoText;
    private TextView serverUrlText;
    private LinearLayout savedContainer;
    private LinearLayout scanContainer;
    private EditText ssidInput;
    private EditText passwordInput;
    private final Handler handler = new Handler();

    private final Runnable refreshInfoTask = new Runnable() {
        @Override
        public void run() {
            refreshInfo();
        }
    };

    private final Runnable refreshSavedTask = new Runnable() {
        @Override
        public void run() {
            refreshSavedNetworks();
        }
    };

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                refreshScanResults();
            }
            refreshInfo();
            refreshSavedNetworks();
        }
    };

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshInfo();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        setContentView(buildUi());

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiReceiver, filter);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } else {
            startScan();
        }

        refreshInfo();
        refreshSavedNetworks();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshInfo();
        refreshSavedNetworks();
        handler.post(periodicRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(periodicRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(wifiReceiver);
        } catch (IllegalArgumentException ignored) {
            // was never registered (e.g. destroyed before onCreate finished) — fine
        }
    }

    // ---- UI construction: plain code, no layout XML/resources to compile ----

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Miko 3 — Custom Launcher (PoC)");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Replaces the stock home screen. See docs/hardware/ in the repo for everything else.");
        subtitle.setTextColor(Color.GRAY);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

        infoText = new TextView(this);
        infoText.setTextSize(15);
        infoText.setPadding(0, 0, 0, dp(8));
        root.addView(infoText);

        serverUrlText = new TextView(this);
        serverUrlText.setTextSize(16);
        serverUrlText.setTextColor(Color.parseColor("#2e7d32"));
        serverUrlText.setPadding(0, 0, 0, dp(24));
        root.addView(serverUrlText);

        root.addView(sectionLabel("Wi-Fi"));

        Button disconnectBtn = new Button(this);
        disconnectBtn.setText("Disconnect");
        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wifiManager.disconnect();
                Toast.makeText(MainActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
                handler.postDelayed(refreshInfoTask, 1000);
            }
        });
        root.addView(disconnectBtn);

        root.addView(sectionLabel("Saved networks"));
        savedContainer = new LinearLayout(this);
        savedContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(savedContainer);

        root.addView(sectionLabel("Connect manually"));
        ssidInput = new EditText(this);
        ssidInput.setHint("SSID");
        root.addView(ssidInput);

        passwordInput = new EditText(this);
        passwordInput.setHint("Password (leave blank for an open network)");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passwordInput);

        Button connectBtn = new Button(this);
        connectBtn.setText("Connect");
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectManually();
            }
        });
        root.addView(connectBtn);

        LinearLayout scanHeader = new LinearLayout(this);
        scanHeader.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams weightLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scanHeader.addView(sectionLabel("Nearby networks"), weightLp);
        Button rescanBtn = new Button(this);
        rescanBtn.setText("Rescan");
        rescanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        scanHeader.addView(rescanBtn);
        root.addView(scanHeader);

        scanContainer = new LinearLayout(this);
        scanContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanContainer);

        return scroll;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setPadding(0, dp(16), 0, dp(6));
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(v * density);
    }

    // ---- data refresh ----

    private void refreshInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(DeviceInfo.model()).append('\n');
        sb.append("Serial: ").append(DeviceInfo.serial()).append('\n');
        int battery = DeviceInfo.batteryPercent(this);
        sb.append("Battery: ").append(battery < 0 ? "unknown" : battery + "%").append('\n');
        sb.append("Uptime: ").append(DeviceInfo.uptime()).append('\n');
        sb.append("Wi-Fi: ").append(DeviceInfo.wifiSsid(this));
        infoText.setText(sb.toString());

        String ip = DeviceInfo.wifiIp(this);
        if (!ip.isEmpty()) {
            serverUrlText.setText("Web page: http://" + ip + ":" + InfoHttpServer.PORT + "/");
        } else {
            serverUrlText.setText("Connect to Wi-Fi to enable the web page");
        }
    }

    private void refreshSavedNetworks() {
        savedContainer.removeAllViews();
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null || configs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("(none saved)");
            none.setTextColor(Color.GRAY);
            savedContainer.addView(none);
            return;
        }
        for (WifiConfiguration c : configs) {
            final int networkId = c.networkId;
            final String label = DeviceInfo.stripQuotes(c.SSID);
            savedContainer.addView(networkRow(label, null,
                    new Runnable() {
                        @Override
                        public void run() {
                            wifiManager.enableNetwork(networkId, true);
                            wifiManager.reconnect();
                            Toast.makeText(MainActivity.this, "Connecting to " + label, Toast.LENGTH_SHORT).show();
                            handler.postDelayed(refreshInfoTask, 2000);
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            wifiManager.removeNetwork(networkId);
                            wifiManager.saveConfiguration();
                            refreshSavedNetworks();
                        }
                    }));
        }
    }

    private void refreshScanResults() {
        scanContainer.removeAllViews();
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            TextView t = new TextView(this);
            t.setText("(location permission needed to scan)");
            t.setTextColor(Color.GRAY);
            scanContainer.addView(t);
            return;
        }
        if (results == null || results.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("(no networks found — tap Rescan)");
            t.setTextColor(Color.GRAY);
            scanContainer.addView(t);
            return;
        }
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            String security = r.capabilities != null && r.capabilities.contains("WPA") ? "secured" : "open";
            String label = r.SSID + "  (" + security + ", " + r.level + " dBm)";
            final String ssid = r.SSID;
            scanContainer.addView(networkRow(label,
                    new Runnable() {
                        @Override
                        public void run() {
                            ssidInput.setText(ssid);
                            passwordInput.requestFocus();
                        }
                    }, null, null));
        }
    }

    private void connectManually() {
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (ssid.isEmpty()) {
            Toast.makeText(this, "Enter an SSID", Toast.LENGTH_SHORT).show();
            return;
        }
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        if (password.isEmpty()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = "\"" + password + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        int id = wifiManager.addNetwork(config);
        if (id == -1) {
            Toast.makeText(this, "Failed to add network", Toast.LENGTH_SHORT).show();
            return;
        }
        wifiManager.enableNetwork(id, true);
        wifiManager.reconnect();
        wifiManager.saveConfiguration();
        Toast.makeText(this, "Connecting to " + ssid, Toast.LENGTH_SHORT).show();
        handler.postDelayed(refreshInfoTask, 2000);
        handler.postDelayed(refreshSavedTask, 2000);
    }

    private void startScan() {
        boolean started = wifiManager.startScan();
        if (!started) {
            Toast.makeText(this, "Scan request failed (throttled?)", Toast.LENGTH_SHORT).show();
        }
    }

    /** One row: a (optionally clickable) label plus up to two action buttons. */
    private View networkRow(String label, Runnable onLabelClick, Runnable onConnect, final Runnable onForget) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView text = new TextView(this);
        text.setText(label);
        if (onLabelClick != null) {
            final Runnable click = onLabelClick;
            text.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    click.run();
                }
            });
            text.setTextColor(Color.parseColor("#1565c0"));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, lp);

        if (onConnect != null) {
            final Runnable connectAction = onConnect;
            Button connect = new Button(this);
            connect.setText("Connect");
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectAction.run();
                }
            });
            row.addView(connect);
        }
        if (onForget != null) {
            Button forget = new Button(this);
            forget.setText("Forget");
            forget.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onForget.run();
                }
            });
            row.addView(forget);
        }
        return row;
    }
}
