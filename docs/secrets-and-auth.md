# Secrets, auth, and provisioning (redacted)

> Values are redacted here on purpose. Full values live in the gitignored
> `secrets/` directory — see `secrets/README.md`. The `GLOBAL_AUTH_TOKEN` and
> client cert may be shared across units, so publishing them would enable
> attacks on other people's robots.

Everything below was found in plaintext on the device filesystem
(`/storage/emulated/0/klug/`), readable by the robot's own apps (which run as
`system`, uid 1000). None of it is protected by hardware keys.

## Where the secrets live

| Location | Contents |
|----------|----------|
| `/storage/emulated/0/klug/miko.properties` | `GLOBAL_AUTH_TOKEN`, SSL passwords, every backend URL |
| `/storage/emulated/0/klug/ssl/node.p12` | mutual-TLS client cert (password in `miko.properties`) |
| `/storage/emulated/0/klug/OTA_1100/aes_key_security_miko3.bin` | AES-256 key for the provisioning handshake |
| `/storage/emulated/0/klug/OTA_1100/key_for_command.bin` | second 32-byte key (command channel) |
| `/storage/emulated/0/klug/OTA_1100/testing_all_in_one.sh` | provisioning script (self-deleting) |
| `/storage/emulated/0/klug/TESTING_ENC/p1_test_37.bin` | test key |
| `/storage/emulated/0/klug/g5nyhkzq7ax7v8sx1g3oqg/*.sqlite` | derived `API_STATUS` token |
| `/storage/emulated/0/klug/APPS/appStore.sqlite` | `PARENT_ID_BOT_LINKED` |

## `miko.properties`

A plain `key=value` file. Notable keys:

```
GLOBAL_AUTH_TOKEN=<32 hex chars>          # shared/global bearer token
SSL_CERTIFICATE_PASSWORD=<...>            # unlocks node.p12
API_SSL_CERTIFICATE_PASSWORD=<...>        # API-specific p12 password
ENABLE_ADB=N                              # they explicitly disable adb
SYSTEM_UPDATE=APPS_latest.z               # OTA payload name
```

Plus ~20 backend URLs (`prod-login-central`, `prod-appstore`, `prod-miko`,
`prod-userproperties`, `m3usa1-prod-central`, all under `miko-robot.in`).
Several API paths are **hex-encoded ASCII** in the config, e.g.
`/v4/676574617070436F6E66696775726174696F6E/` decodes to
`/v4/getappConfiguration/`.

## Provisioning handshake (`testing_all_in_one.sh`)

The robot derives a per-unit API token from its own hardware identifiers:

```
X = sha256( mac_address + parent_id + epoch_ms )
Y = sha512( X )
Z = sha256( reverse(Y) )
repeat 106 times:  Z = sha256( Z + X )
```

Then it AES-256-CBC encrypts (key = `aes_key_security_miko3.bin`, random IV
prepended, base64) the timestamp and the Wi-Fi MAC, and POSTs:

```
POST /process_Timestamp_4th_March HTTP/1.1
Host: prod-login-central.miko-robot.in
i: <base64( IV || AES(timestamp) )>
w: <base64( IV || AES(mac) )>
Content-Type: application/json

{"parent_id":"<id>","username":"<serial[10:19]>","product_id":1}
```

On HTTP 200 it stores `Z` as `API_STATUS` in the sqlite db, then deletes the AES
key file and the script itself. That `Z` is the robot's long-lived API credential.

### Why this matters for jailbreaking

- The API credential is derived from **device identifiers we can read** (MAC,
  serial) plus a **plaintext AES key** on the sdcard. Nothing is hardware-backed.
- The auth "secret" is therefore recoverable from any unit with filesystem read
  access — and the robot's own apps have that by design.
- This is the same class of issue mgdproductions reported (leaked keys →
  backend access). It is a design weakness, not a bug we can patch from here.

## How we obtained and derived each secret

Prerequisite: root ADB. Either **factory mode** (`scripts/factory-root.sh`) or
**normal mode** with `persist.sys.usb.config=mtp,adb` gives `uid=0(root)`.

### Step 1 — locate the plaintext store

The robot keeps its config on the sdcard, not in app-private storage:

```bash
adb shell 'find /storage/emulated/0/klug -maxdepth 2 -type f \
  \( -name "*.properties" -o -name "*.p12" -o -name "*.bin" \
     -o -name "*.sqlite" -o -name "*.sh" \)'
```

That returned `miko.properties`, `ssl/node.p12`, `OTA_1100/*.bin`,
`OTA_1100/testing_all_in_one.sh`, the sqlite dbs, and `TESTING_ENC/p1_test_37.bin`.
The very first clue was in `docs/findings.md`'s app-data dump: the analytics db
referenced `/storage/emulated/0/klug/downloads/ENC/file2.ia`, which led to the
whole `klug/` tree.

### Step 2 — `miko.properties` (token + passwords + URLs)

Straight `adb pull`, it is a world-readable-ish `0644`/`0660` file:

```bash
adb pull /storage/emulated/0/klug/miko.properties secrets/miko.properties
grep -E 'TOKEN|PASSWORD|_URL|HOSTNAME' secrets/miko.properties
```

No derivation — the values are literal. `GLOBAL_AUTH_TOKEN`,
`SSL_CERTIFICATE_PASSWORD`, `API_SSL_CERTIFICATE_PASSWORD`, and ~20 endpoint
URLs all sit in this one file. The hex-encoded API paths were decoded with:

```bash
printf '676574617070436F6E66696775726174696F6E' | xxd -r -p   # getappConfiguration
printf '6C6F67696E5F75736572'                   | xxd -r -p   # login_user
```

### Step 3 — the AES keys

```bash
adb pull /storage/emulated/0/klug/OTA_1100/aes_key_security_miko3.bin secrets/ota-keys/
adb pull /storage/emulated/0/klug/OTA_1100/key_for_command.bin          secrets/ota-keys/
adb pull /storage/emulated/0/klug/TESTING_ENC/p1_test_37.bin            secrets/ota-keys/
xxd secrets/ota-keys/aes_key_security_miko3.bin   # 32 bytes -> AES-256 key
```

No derivation — raw 32-byte keys. Their **use** is documented by
`testing_all_in_one.sh` (step 5 below).

### Step 4 — the client certificate (`node.p12`)

The password is in `miko.properties`. The bundle is legacy RC2, so OpenSSL 3
needs `-legacy`:

```bash
adb pull /storage/emulated/0/klug/ssl/node.p12 secrets/certs/
openssl pkcs12 -in node.p12 -out node.pem -passin pass:emotixMIKO -legacy
# -> private key + client cert, usable as: curl --cert node.pem:<pass> …
```

We verified it was a real keypair (not just a cert) by the successful `-legacy`
extract; the `tlsMIKO3` value from `miko.properties` is the API-side password.

### Step 5 — the provisioning token derivation (`testing_all_in_one.sh`)

This script *is* the derivation. We pulled it and read it:

```bash
adb pull /storage/emulated/0/klug/OTA_1100/testing_all_in_one.sh secrets/
```

What it computes (see the script for the exact shell):

```
parent   = PARENT_ID_BOT_LINKED from appStore.sqlite (SharedPrefMaster)
mac      = `ip link show wlan0`
epoch_ms = `date +%s%3N`

X = sha256( mac + parent + epoch_ms )
Y = sha512( X )
Z = sha256( reverse(Y) )
for i in 1..106:  Z = sha256( Z + X )        # <-- the API token
```

Then it encrypts the handshake headers with the step-3 key:

```
key  = aes_key_security_miko3.bin          (AES-256)
IV   = openssl rand -hex 16
c    = openssl enc -aes-256-cbc -K <key_hex> -iv <IV> -nosalt
header i = base64( IV || c(epoch_ms) )
header w = base64( IV || c(mac)      )
```

and POSTs to the decoded endpoint:

```
POST /process_Timestamp_4th_March  ->  prod-login-central.miko-robot.in:443
{"parent_id":"<parent>","username":"<serial[10:19]>","product_id":1}
```

On HTTP 200 it writes `Z` into `g5nyhkzq7ax7v8sx1g3oqg.sqlite` as
`KeyValueMaster.API_STATUS`, then deletes the AES key and itself. So the stored
`API_STATUS` value (if present) *is* the derived token — no further derivation
needed. We pulled that db to check:

```bash
adb pull /storage/emulated/0/klug/g5nyhkzq7ax7v8sx1g3oqg/g5nyhkzq7ax7v8sx1g3oqg.sqlite secrets/db/
sqlite3 secrets/db/g5nyhkzq7ax7v8sx1g3oqg.sqlite \
  "select * from KeyValueMaster;"
```

### Step 6 — device identifiers used as derivation inputs

```bash
adb shell getprop ro.serialno        # MIKO3250XXM3Q0636CB  -> bot id = chars 11-19
adb shell 'ip link show wlan0'       # MAC = derivation input for the token
adb shell getprop ro.build.fingerprint
```

`serial[10:19]` is the "username" in the provisioning payload; the MAC is both a
header input and part of the `X` hash.

### Summary table — obtained vs derived

| Secret | How | Derivation |
|--------|-----|------------|
| `GLOBAL_AUTH_TOKEN` | read `miko.properties` | none (literal) |
| SSL / API passwords | read `miko.properties` | none (literal) |
| Backend URLs | read `miko.properties` | hex-decode some paths |
| `aes_key_security_miko3.bin` | read sdcard file | none (raw key) |
| `key_for_command.bin` | read sdcard file | none (raw key) |
| `p1_test_37.bin` | read sdcard file | none (raw key) |
| `node.p12` + private key | read sdcard file, `openssl -legacy` | password from `miko.properties` |
| `API_STATUS` token `Z` | read `g5nyhkzq7ax7v8sx1g3oqg.sqlite` | `sha256` loop over MAC+parent+time (step 5) |
| bot id / MAC / serial | `getprop`, `ip link` | substring + used as hash inputs |

## OTA payloads are not encrypted

Despite the `.ia` extension, the APK payloads inside `APPS.zip` are **plain
zips/APKs**. No decryption is needed to extract or inspect them. The `ENC/`
directory in `downloads/` is where the update app stages `APPS.zip` before
unpacking; the `enc_*` naming there is the *inbound* direction of the app's own
`KeyUtils.encryptFile`, not protection on the published OTA.

## Responsible-handling note

We are documenting **structure and locations** publicly, and keeping **values**
local. If you extract these from your own unit, do not publish them: the global
token and client cert are not per-device and could be abused against other
owners' robots and against Miko's backend. The provisioning script itself is
also a live credential-derivation path.
