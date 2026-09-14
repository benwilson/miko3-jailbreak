# Miko 3 custom launcher (proof of concept)

Replaces the stock HOME app (`com.miko.launcher_app`) with a minimal launcher
that shows basic device info on-screen, serves the same info as a read-only
web page over Wi-Fi, and offers basic Wi-Fi connect/disconnect/forget — the
stock kiosk has no reachable Settings screen, so without this the device has
no way onto a network at all.

No Gradle, no AndroidX, no third-party libraries: plain Java against the
Android 28 platform jar, built the same way as `bootagent/` (see
`scripts/build-custom-launcher.py`). Lambdas are avoided throughout —
`-bootclasspath android.jar` has no `java.lang.invoke.LambdaMetafactory`, so
javac can't compile lambda syntax against it; anonymous inner classes are
used instead.

## What it does

- **On-screen info**: model, serial, battery %, uptime, current Wi-Fi SSID,
  and (once connected) the web page's URL.
- **Web page**: `InfoHttpServer` is a from-scratch `ServerSocket`-based
  HTTP/1.0 server (no framework) started once per process from
  `LauncherApp.onCreate`, always listening on port `8080`. It answers every
  request with the same generated HTML page — no routing, no file access, no
  query/body parsing, and no device-derived string is ever inserted into the
  page without HTML-escaping. Reachable at `http://<device-ip>:8080/` from
  any browser on the same Wi-Fi network.
- **Wi-Fi management**: saved networks (connect/forget), a manual SSID+password
  connect form, and a nearby-networks scan list — all through the standard
  `android.net.wifi.WifiManager` APIs (`CHANGE_WIFI_STATE` is a normal,
  install-time permission on this API level; no root/privileged path needed).
  `ACCESS_FINE_LOCATION` is requested at runtime, required by this API level
  to get scan results.

## Build, install, restore

```bash
python3 scripts/build-custom-launcher.py          # -> launcher/miko3-launcher.apk
python3 scripts/install-custom-launcher.py        # backs up current HOME state, installs, activates
python3 scripts/restore-stock-launcher.py         # reverts HOME back to com.miko.launcher_app
```

`install-custom-launcher.py` never touches `com.miko.launcher_app` itself —
that app stays installed on the read-only `/system` partition exactly as it
was. All the install does is change Android's persisted HOME preference, and
`restore-stock-launcher.py` changes it back in one `cmd package
set-home-activity` call. Each install run writes a timestamped record to
`firmware/agent-backups/<ts>-custom-launcher-install/` (the previous HOME
component, the restore command, and the installed APK's sha256) before
touching anything, and refuses to proceed if the current HOME state isn't
what it expects rather than guessing a restore target.

One nuance observed while testing: if the custom launcher's task is still
the foreground activity when you run the restore script, pressing the home
key won't visibly flip back to the stock launcher immediately — Android
updates the persisted preference right away (confirmed via
`resolve-activity`), but an already-foregrounded task isn't proactively
evicted. A reboot, or backgrounding to a different app and returning home,
shows the change immediately.

## Verified (2026-09-14, on the actual device)

- Builds and installs cleanly; app launches with no crash.
- Web page confirmed serving live, correct device info via `adb forward
  tcp:8080 tcp:8080` + `curl` (proves the server works independent of Wi-Fi
  being connected to a real network for testing purposes).
- Tapping "Connect" on a saved network correctly fires
  `CMD_ENABLE_NETWORK`/`CMD_START_CONNECT` from the app's own uid (confirmed
  in `dumpsys wifi`'s state-machine log) — the code path is correct. Full
  association wasn't observed in this test only because the specific saved
  AP wasn't in radio range of the device at the time; this is an environment
  fact, not an app bug.
- Full install → restore → reinstall round-trip completed with `resolve-activity`
  confirming the HOME component at each step.

## Known limitations (proof of concept, by design)

- Web page auto-refreshes every 10s; no push/websocket.
- No HTTPS — plain HTTP only, fine for a same-LAN PoC.
- No auth on the web page or the Wi-Fi management UI — anyone on the same
  Wi-Fi network, or anyone with physical access to the device's screen, can
  use it. Acceptable for a PoC on a personally-owned device; would need
  addressing before any broader use.
- WEP networks aren't supported (only open and WPA/WPA2-PSK) — not a real
  gap in practice.
