# Firmware download investigation — what we tried (2026-09-12)

Status: **informational**. Not confirmed conclusions — this is a record of the
paths we tried, what answered, and where we hit walls. Update the "current
state" section if you re-run these later.

## Goal

Get a fresh copy of the OTA (`APPS.zip`) directly from Miko, the same way the
update app does, so we have an independent reference (not just the copy already
sitting on our unit).

## The download chain (from the decompiled update app)

Two-stage: **check** then **fetch**. Both live in `com.miko.app_update`.

1. **Check for updates** — `mikoAPIInterface.checkUpdates`:
   ```
   POST /mikoplus_graphapi/game/WS/mikoplus_graphapi/game_status_update_checkupdate
   Content-Type: application/x-www-form-urlencoded
   apiEmail=<botid>&APIKEY=<GLOBAL_AUTH_TOKEN>&robotid=<botid>
   ```
   Base URL = `NET_URL` = `http://m3-prod-orient-central.miko-robot.in/mikoplus_graphapi/game/WS/`
   (because the `@POST` path starts with `/`, Retrofit replaces the path, so the
   doubled-looking path above is correct).

2. **Fetch the binary** — `ApiInterface.downloadfile`:
   ```
   POST graphapi/game/WS/orientdb/bootloader        (relative -> appended to NET_URL)
   username=<botid>&password=<derived>&version=0&game_id=<id>&vRobotId=<botid>
   ```
   Response `DownloadResponse.response` is the real download URL (a storage URL);
   `BootDownload.downloadFileAndUpdate` opens it with `new URL(response).openStream()`.

3. **First call in the flow** is actually `GameStatusAPI.downloadfile`:
   ```
   POST graphapi/game/WS/orientdb/game_status/select
   vRobotId=<botid>
   ```
   which returns the game list; each game's id feeds stage 2.

### Values for our unit

| Field | Value |
|-------|-------|
| `botid` / `username` / `robotid` / `vRobotId` | `M3Q0636CB` (= `serial[10:19]`) |
| `APIKEY` | `GLOBAL_AUTH_TOKEN` from `miko.properties` |
| `password` | derived: `substring(13,19)` of serial, parsed as hex, `(x+273)*2`, `%06X` → for us `0C6FB8` |
| `game_id` | unknown (comes from the `game_status/select` response) |

## Hosts and what they did

| Host | DNS | Ports | Result |
|------|-----|-------|--------|
| `m3-prod-orient-central.miko-robot.in` (the configured `NET_URL` host) | `34.135.25.196` | 80/443 **closed** ("punt!") | direct connection fails |
| `m3usa1-prod-central.miko-robot.in` | `34.28.93.218` | 80 open, 443 open | serves appstore, graph paths 404 |
| `prod-appstore.miko-robot.in` | `34.117.93.89` | 443 open | **alive** — `GET /miko3/appstore/v2/bot` → `{"response":"Authentication failed","code":"-1"}` |
| `prod-login-central` / `prod-miko` | `34.117.93.89` | 443 open | alive, app endpoints |
| `miko3-k8s-admin1.miko2.co.in` (factory `miko1.properties`) | no DNS record | — | dead factory host |
| `miko3-aks-ingress.miko2.co.in` (factory) | no DNS record | — | dead factory host |

**Key discovery:** `34.117.93.89` is a shared Google load balancer that fronts
*several* Miko hostnames, including the orient host. The orient host's own DNS
record (`34.135.25.196`) is **stale/dead**, but sending the request to the LB
with `Host: m3-prod-orient-central.miko-robot.in` (and matching SNI) reaches the
right backend:

- HTTP to the LB with the orient Host → `301` to `https://m3-prod-orient-central...:443/...`
- HTTPS to the LB with orient SNI → **`503 Service Unavailable`,
  body `unconditional drop overload`**

`unconditional drop overload` is a Google Cloud load-balancer message meaning the
backend service has no healthy instances. It is **not** an auth failure and
**not** a User-Agent/WAF block (we tried `okhttp/3.12.0` and plain).

## Walls hit

1. **Configured host DNS is stale.** `m3-prod-orient-central` → a dead IP. The
   service actually lives behind the shared LB; you must override Host/SNI.
2. **Graph API backend returns 503.** Consistent across retries and both the
   orient and US SNI. The `mikoplus_graphapi` backend appears down / drained.
3. **US host does not serve the graph API.** All `/mikoplus_graphapi/...` paths
   404 there; only `/appstore/...` (503) and app endpoints answer.
4. **Factory hosts are gone.** `*.miko2.co.in` no longer resolve.
5. **Appstore is alive but gated.** `prod-appstore.miko-robot.in/miko3/appstore/v2/bot`
   returns `Authentication failed`. The provisioning flow (`testing_all_in_one.sh`)
   authenticates with AES-256-CBC headers `i` (timestamp) and `w` (Wi-Fi MAC),
   keyed by `aes_key_security_miko3.bin` — see `docs/secrets-and-auth.md`. We
   built those headers but have not yet found an appstore path that returns the
   firmware.

## Why this may not matter

The unit already holds the most recent payload it downloaded:
`/storage/emulated/0/klug/downloads/APPS.zip` (mtime **2026-09-10 13:24** — two
days before this investigation). So we already have a full, self-consistent
reference for the current update. A fresh download would mainly confirm that
this is the latest and that the service still hands it out.

## Open threads (if we revisit)

- Retry the graph API later — 503 may be transient capacity, not decommission.
- Authenticate to the appstore with `i`/`w` and enumerate its paths; the
  firmware may be reachable through it even if the graph API is down.
- Look for a storage URL in the appstore response schema (GCS bucket, possibly
  the same bucket mgdproductions described).
- Check whether the launcher (`com.miko.launcher_app`) or ServiceExam has its
  own download path with a different host than the update app.

## Exact request we can reproduce (once a backend is healthy)

```
POST /mikoplus_graphapi/game/WS/mikoplus_graphapi/game_status_update_checkupdate HTTP/1.1
Host: m3-prod-orient-central.miko-robot.in
User-Agent: okhttp/3.12.0
Content-Type: application/x-www-form-urlencoded
Content-Length: <len>
Connection: close

apiEmail=M3Q0636CB&APIKEY=<GLOBAL_AUTH_TOKEN>&robotid=M3Q0636CB
```

Send to `34.117.93.89:443` with SNI `m3-prod-orient-central.miko-robot.in`.
Scripts used: `scripts/probe-update-api.sh` (to be added).
