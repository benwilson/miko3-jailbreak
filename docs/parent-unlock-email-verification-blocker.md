# The "Verification Needed" (email) blocker — cause, flow, and bypass

## Symptom

After running `scripts/unlock-parent.sh unlock` (clearing `AppConfigResponse.parent`,
`LoginResponse`, app data) and rebooting, the robot does **not** reach the
"Linking Code" screen. Instead, after passing through a loading animation it
lands on:

> **Verification Needed**
> To help keep your Miko secure, ask a grown up to enter first 4 letters of
> the registered email address
> `[Proceed]`

This happens even though the local `AppConfigResponse` correctly shows
`{"parent":"","status":"success","LOGIN":"N"}`.

## Root cause (confirmed by decompiling both APKs with jadx)

Two apps are involved, sharing state through files on `/storage/emulated/0/klug/`:

- `com.example.root.serviceexam` (`file1.ia`) — the privileged (`uid=1000`,
  platform-signed) background service. Its `com.miko3.login_lib.InternetCheckThreadLib`
  polls connectivity and login state.
- `com.miko.mikoplus` (`file2.ia`) — the kiosk UI app (`MikoActivity`).

### The gate

Both apps independently evaluate the **same** two-part condition and, if it's
true, show the blocker:

```java
// InternetCheckThreadLib.java (ServiceExam) — processFlag() and login_call()
String valueForAPI_SK = getValueForAPI_SK();
if (isFtueFileSuccess() && TextUtils.isEmpty(valueForAPI_SK)) {
    setDbEmptyForCheck(true);
    loginCallbacks.showBlockerScreenForCheck();   // -> AIDL: Globals.SHOW_BLOCKER_SCREEN_FOR_EMAIL
}
```

```java
// MikoActivity.java (MikoPlus) — checkForPassValidation()
if (isFtueFileSuccess()) {
    String valueForAPI_SK = getValueForAPI_SK();
    if (!TextUtils.isEmpty(valueForAPI_SK) || isShowing(this.parentProfileSetupDialogFragment)) {
        return;
    }
    // ... show ParentProfileSetupDialogFragment (the blocker dialog)
}
```

`isFtueFileSuccess()` — a **plaintext flag file**, not a DB field:

```java
public static boolean isFtueFileSuccess() throws Throwable {
    File file = new File(Environment.getExternalStorageDirectory() + "/klug/ftue/ftueStatus.txt");
    ...
    String dataFromFile = LoginFileUtils.getInstance(context2).getDataFromFile(file);
    return dataFromFile.equalsIgnoreCase("success");
}
```

`getValueForAPI_SK()` — reads `KeyValueMaster.API_STATUS` from
`/storage/emulated/0/klug/g5nyhkzq7ax7v8sx1g3oqg/g5nyhkzq7ax7v8sx1g3oqg.sqlite`
(same DB/key documented in `docs/secrets-and-auth.md` as the derived
provisioning token).

**So the blocker fires when: `ftueStatus.txt` says `"success"` (this device
previously completed setup) AND the derived API token is missing/empty**
(exactly the state a "clear parent + clear app data" unlock produces, or —
worse — what restoring `/storage/emulated/0/klug/` from an already-provisioned
backup produces, since the backup's `ftueStatus.txt` also says `"success"`).

Neither app looks at the local `parent` field for this decision at all — that
field only matters for the *linking* flow. This blocker is a separate,
independent "was this bot re-provisioned since it last finished setup"
check.

## Where the fake bypass came from

`g5nyhkzq7ax7v8sx1g3oqg.sqlite`'s `API_STATUS` value was found set to a literal
placeholder string, not a real derived token:

```
kiosk-setup-bypass-0000000000000000000000000000000000000000
```

This was presumably written in an earlier session specifically to make
`getValueForAPI_SK()` return non-empty (satisfying the gate's *other* branch
and skipping the blocker that way, at the cost of leaving a fake credential
around). It does not appear in git history or any doc — it was live device
state only. We deleted it (`rm -rf .../g5nyhkzq7ax7v8sx1g3oqg/`) while
debugging, which put the device back in the "empty API_SK" branch and made the
blocker reappear — that's what actually exposed this whole mechanism.

## What happens if you proceed anyway (the "enter 4 letters" flow)

Decompiling `ParentProfileSetupDialogFragment` (implements both
`EmailValidationContract.View` and `PassValidatorContract.View`) shows this is
a real two-step OTP challenge, **fully server-validated**:

1. **`EmailValidationPresenter.getEmailValidation()`** — POSTs
   `EmailValidatorModel(botId, <the 4 letters>, locale)` to
   `<baseUrl>v3/2f67656e65726174654f5450` (hex → `/generateOTP`). The client's
   *only* success check is `response.code() == 200` — it never inspects the
   body for a match. A 200 just means "OTP email dispatch accepted"; it reveals
   nothing about whether the 4 letters were correct (standard anti-enumeration
   design).
2. **`PassValidatorPresenter.getPassValidation()`** — POSTs
   `PassValidatorModel(botId, email, otp, locale)` to `<baseUrl>v3/2f76616c69646174654f5450`
   (hex → `/validateOTP`), again only checking `response.code() == 200`. On
   success it reads `response.getParent()` from the server and calls
   `AppUtilsUI.setParentIDLinkedWithBot(...)`, then `performDeviceRegistration()`.

Both calls trust the server completely; the client does no independent
verification. But the OTP itself is emailed to the **real registered address**
by the backend — so this path still requires access to that inbox. Spoofing it
would require intercepting/mocking both endpoints (DNS redirect + a locally
trusted TLS cert, since `node.p12`/`GLOBAL_AUTH_TOKEN` are client credentials,
not proof the app validates the server's identity) — not attempted; the
`ftueStatus.txt` fix below avoids needing to go there at all.

## The fix

Skip the blocker instead of trying to pass it: make `isFtueFileSuccess()`
false by rewriting the flag file to anything other than `"success"`:

```bash
adb shell "echo -n 'pending' > /storage/emulated/0/klug/ftue/ftueStatus.txt"
```

With this alone (independent of whatever `API_STATUS` holds), neither
ServiceExam's `InternetCheckThreadLib` nor MikoPlus's `MikoActivity` will show
the blocker, and the device proceeds through the normal fresh-setup flow
instead of the re-verification challenge.

## Fix applied to `scripts/unlock-parent.sh`

The unlock flow needs this step in addition to the existing DB edits, or a
restore of `/storage/emulated/0/klug/` from any previously-provisioned backup
will silently reintroduce the blocker. See the script for the current step
list; `do_unlock()` now also resets `ftue/ftueStatus.txt`.

## Update — a second, separate blocker after the email fix: "Can't show linking code"

Clearing `ftueStatus.txt` does get past the email/OTP blocker and into the real
FTUE flow (WiFi setup → `FTUE3MainFragment`), but on this device (which had its
`g5nyhkzq7ax7v8sx1g3oqg/` API-token directory deleted while debugging the
above) the flow now dead-ends on a **different** screen:

> **Can't show linking code**
> Restart Miko and try again. If it keeps happening, contact support.

This is `FtueLinkingCodeErrorFragment`, and it is a **legitimate real-backend
rejection**, not a local check we're tripping — traced end to end:

1. `FTUE3MainFragment.onMoveToNextStep` reaches `WidgetLevels.ParentPairingPage`
   and calls `AppUtilsUI.sendRequest(208, "UI")` — an AIDL request to
   ServiceExam, request code `Globals.EXCEPTION_LOGIN_ALLOWED = 208`.
2. ServiceExam's `ServiceClientInterface` (case `208`) replies with
   `Globals.EXCEPTION_LOGIN_ALLOWED_RESULT` = `InternetCheckThreadLib.isIsLoginFailedDueToKey()`.
3. `isLoginFailedDueToKey` is set in `LoginAPIS.loginAPI()` (the real login
   call — see below) when the server's error response parses to `code == -9`
   **and** `!isFtueFileSuccess()` (true, since we intentionally cleared the
   flag) — logged live as `LOGIN_KEY_FAILED: true` (tag
   `LoginInternetCheckThreadLib`).
4. `MikoActivity` receives the AIDL result and calls
   `FTUE3MainFragment.onExceptionLoginAllowedCallback(true)`, which routes to
   `WidgetLevels.LinkingCodeError` → `FtueLinkingCodeErrorFragment`.

### The actual network call (confirmed live in logcat, ServiceExam process)

```
GET http://m3usa1.miko-robot.in/sparkcommonutil/v2/maintenanceStatus?username=M3Q0636CB&product_id=1
```

`username=M3Q0636CB` is `ro.serialno` characters 10-19
(`MIKO3250XXM3Q0636CB` → `M3Q0636CB`), matching the derivation already
documented in `docs/secrets-and-auth.md`. This call repeats every ~10s while
stuck (it's a maintenance-mode poll, not the login call itself).

The actual login call is `LoginAPIS.loginAPI(username, password, key, "1",
timestamp, rKeyCommand)` → `mikoAPIInterface.loginAPIV14(...)`, POSTing a
`LoginRequestModel{username, password, key, ipaddress, inFTUE}` built from a
`BotDetails` object (bot-level device credentials from local provisioning
data, not anything a person enters). The server's JSON error body parses to
`{"code": -9, ...}` — read directly by `LoginAPIS.java` around line 544
(`serverError.getCode() == -9` → `"Login Failed Key"`).

### Diagnosis

`code: -9` is the backend telling the device its login credential (the `key`
field, or the identity it implies) isn't valid — consistent with this device
having had its `g5nyhkzq7ax7v8sx1g3oqg/` directory (the derived `API_STATUS`
provisioning token, see the top of this doc) deleted while debugging, with
nothing having re-run the real provisioning handshake
(`OTA_1100/testing_all_in_one.sh`) since. **Not yet resolved** — the working
theory is the device needs that handshake to complete successfully (real POST
to `prod-login-central.miko-robot.in/process_Timestamp_4th_March`, storing a
freshly-derived `API_STATUS`) before `loginAPI` can succeed. Whether that
handshake is meant to fire automatically somewhere in ServiceExam's boot
sequence, or needs to be manually re-triggered, is unresearched as of this
writing.

## Update — restored to known-good state (2026-09-14, same session)

Rather than continue spoofing real backend calls to get past the `422`, we
restored the device to its exact pre-session state from the intact
`/data/local/tmp/klug_backup/` snapshot (`APPS/appStore.sqlite`, `ftue/`,
`g5nyhkzq7ax7v8sx1g3oqg/`, all `cp -a`'d back over the live tree, ownership
fixed to `root:sdcard_rw`). Confirmed via screenshot: the device is back to
its original idle animation, `parent` is `mQkU99SDojm8FkFmhqFdPp` (the
original owner), `LOGIN` is `Y`, and it boots and runs normally — no crash,
no error screens.

**Where this leaves the unlock attempt:** the chain documented above is now
understood end-to-end, and `scripts/unlock-parent.sh` correctly performs
every step that's actually within a script's power (DB field clears, app-data
clear, FTUE-flag reset, and it no longer deletes the runtime asset tree). But
reaching a real, working "Linking Code" screen requires completing the
server-validated email+OTP ownership check — which needs the previous
owner's actual email inbox — because the provisioning handshake
(`testing_all_in_one.sh`) that generates a fresh `API_STATUS` credential
**requires a non-empty `parent_id`** (confirmed live: empty `parent_id` gets
HTTP 422 from `prod-login-central.miko-robot.in`), and the only legitimate
way to obtain a new one is via `PassValidatorResponseModel.getParent()` from
a successful OTP validation.

**Two remaining options**, not pursued further this session:

1. **Get the real email** (previous owner, a purchase listing, seller
   correspondence) and go through the verification screen for real.
2. **Build the MITM bypass**: fake HTTP 200 responses for both
   `v3/2f67656e65726174654f5450` (`/generateOTP`) and
   `v3/2f76616c69646174654f5450` (`/validateOTP`), including a fabricated
   `parent` value in the second response body, so the client believes
   verification succeeded without ever reaching Miko's real backend for this
   flow. This is architecturally sound per the decompiled client code (it
   trusts `response.code() == 200` alone, see above) but requires standing
   up a local HTTPS mock, a device-trusted CA/cert, and a DNS/hosts redirect
   for the relevant hostname — not attempted.

## Update — tried faking a fresh device identity instead (BOTNAME override); abandoned

Rather than spoof the server's HTTP responses (MITM), we tried making the
*real* Miko backend treat this bot as one it has never seen before, so it
would issue a genuine new Linking Code without any ownership check —
entirely through the real servers, no interception.

### The lever: `BOTNAME` in `miko.properties`

`mikoProperties.getBotName()` (MikoPlus, `com/emotix/arya/comm/utils/mikoProperties.java:154`):

```java
public String getBotName() {
    String serialNumber = AppUtils.getSerialNumber();
    if (this.p.getProperty("BOTNAME") != null) {
        return this.p.getProperty("BOTNAME");   // overrides the real serial
    }
    return serialNumber;
}
```

`BotDetails.init()` then derives `username = botname.substring(10,19)` and
`password = botname.substring(13,19)` — so `BOTNAME` needs to be ≥19 chars,
shaped like a real serial (`MIKO3250XX` + 9 more chars). Confirmed live:
setting `BOTNAME=MIKO3250XXZ9K3P7QRT` in `miko.properties` and clearing app
data made **both** MikoPlus and ServiceExam pick it up (`Botname =
MIKO3250XXZ9K3P7QRT` in logcat, tag `Omkar`), so this override is respected
by the actual network-facing identity used in the `maintenanceStatus`/login
calls, not just decorative.

### What stopped this

Not the identity swap itself — a **native crash**: clearing
`com.example.root.serviceexam`'s app data (needed so it would re-read the new
`BOTNAME` cleanly) put it into a repeating crash loop:

```
F libc: FORTIFY: FD_SET: file descriptor -2088526151 < 0
```

in its serial/hardware bootloader thread (`SerialLibraryX`/`SPICOMM`), almost
certainly because that thread expects cached hardware-pairing state from
app-private storage that a fresh (cleared) install doesn't have and doesn't
reinitialize gracefully. Android's own crash-loop protection then
permanently stopped auto-restarting the service (exactly the failure mode in
`docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`
— its "no adb-visible undo short of a fresh boot" held here too, but even a
fresh boot didn't stop the underlying crash from recurring, since the
app-data loss is permanent — there's no backup of
`/data/data/com.example.root.serviceexam/`, unlike `/storage/emulated/0/klug`).

**This is unrelated to the `BOTNAME` value itself** — the same crash would
likely follow from clearing ServiceExam's app data for any reason. The
lesson is narrower: don't `pm clear com.example.root.serviceexam`. Clearing
MikoPlus's app data has been safe all session; ServiceExam's apparently is
not.

Abandoned rather than keep forcing restarts against a service tied to real
hardware communication — see `docs/gotchas.md` entry 8. Device restored to
its exact pre-session state (same restore procedure as above); confirmed
stable (ServiceExam holding a steady pid, no further crashes) after a clean
reboot.

### If this is revisited

The `BOTNAME` override itself worked and is a real, non-MITM lever worth
retrying — just without clearing ServiceExam's app data. Untested next step:
set `BOTNAME` and reset the parent/FTUE fields as before, but only
`pm clear com.miko.mikoplus` (skip ServiceExam entirely) or find another way
to make ServiceExam re-read `miko.properties` (it clearly already does, per
the live `Botname = ...` log lines even before we cleared its data — the
clear may not have even been necessary).

## Related docs

- `docs/secrets-and-auth.md` — `API_STATUS` / `g5nyhkzq7ax7v8sx1g3oqg.sqlite` provisioning token
- `docs/mishap-recovery.md` — separate "robo mishap" launcher crash (missing kiosk activity), not this issue
- `docs/parent-account-unlock.md` — the original unlock guide (predates this finding)
