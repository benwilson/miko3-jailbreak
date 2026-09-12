---
title: Persistent ADB Root (USB + Wi-Fi) - Plan
type: feat
date: 2026-09-11
topic: persistent-adb-root
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
deepened: 2026-09-12
---

# Persistent ADB Root (USB + Wi-Fi) - Plan

## Goal Capsule

- **Objective:** A Miko 3 that boots normally — kiosk running, Wi-Fi on — exposes a root ADB shell over both USB and Wi-Fi, and keeps doing so after every reboot without re-entering factory mode.
- **Means:** An in-repo Android boot agent (a `BOOT_COMPLETED` receiver plus the reused `neuterd` daemon) installed once from a factory-mode root session by registering it under `/data/app`, driven by a small Python toolkit (KTD1).
- **Product authority:** The repo owner/operator, for the single documented unit (`MIKO3250XXM3Q0636CB`).
- **Execution profile:** `code`. `ce-work` implements the toolkit and the agent; the operator performs the one physical action the pipeline cannot — a power cycle — if factory mode persists after the install reboot.
- **Open blockers:** None.

---

## Product Contract

### Summary

A one-time setup installs a small boot agent that makes root ADB survive a normal boot on the Miko 3, reachable over both USB and Wi-Fi while the kiosk keeps running. The agent fires on `BOOT_COMPLETED`, defuses the ServiceExam watchdog in init's global mount namespace, starts adbd on TCP 5555, and exposes adb on the USB gadget. Setup runs from factory-mode root without a package manager, and a Python toolkit builds, installs, verifies, and reverts it.

### Problem Frame

The only root ADB available today is factory mode, reached by power-cycling into the MediaTek preloader and sending the META `FACTFACT` handshake (`scripts/factory-root.sh`). That state has no package manager, no Wi-Fi, and no kiosk, and it ends at the next power cycle. Normal boot has Wi-Fi and the kiosk, but the `com.example.root.serviceexam` watchdog greps for `adbd` and reboots the unit when it finds it, so adb cannot stay up. Reaching normal-boot root therefore costs a power cycle and a timing-sensitive preloader race on every attempt, and wireless access is impossible.

### Requirements

**Boot agent**

- R1. The boot agent runs automatically on every normal boot with no user interaction, triggered by `BOOT_COMPLETED`.
- R2. Before adbd starts, the agent shadows `/system/bin/reboot` with a no-op inside init's global mount namespace, so the ServiceExam watchdog's reboot call has no effect.
- R3. The agent starts adbd and makes it listen on TCP port 5555.
- R4. The agent configures the USB gadget to expose the adb function (`mtp,adb`), so adb is present on the USB interface.
- R5. The agent re-asserts R2 and R4 for the life of the boot, so a later revert by ServiceExam or another component does not drop root ADB.

**Install and arming**

- R6. The toolkit installs the agent from a factory-mode root ADB session without invoking the package manager.
- R7. Installation makes Android start the agent on the next boot without relying on `am start` to clear the post-install stopped state.
- R8. Installation leaves ServiceExam and MikoPlus installed and registered, and the kiosk boots normally afterward.
- R9. Installation backs up every system-state file it modifies before modifying it.

**Transports**

- R10. After a normal boot, a root ADB shell is reachable over USB.
- R11. After a normal boot, a root ADB shell is reachable over Wi-Fi on TCP 5555 whenever the device is on the network.
- R12. The host's ADB public key is authorized on the device, so connecting requires no on-device confirmation.

**Verification and revert**

- R13. The toolkit verifies both transports after a reboot and reports which are up.
- R14. The toolkit removes the agent and restores the device to its pre-install state.

**Build and packaging**

- R15. A signed agent APK is committed so setup needs no Android SDK.
- R16. A build path regenerates the APK from source, including the reused `neuterd` daemon, when the SDK and toolchain are present.

**Safety**

- R17. Setup and revert write only to userdata and system-state files, never to the preloader, bootloader, or any boot-chain partition, and never to `/system` or `/vendor` storage; the agent's only `/system` touch is a runtime bind mount, which is not a write and does not survive a reboot.

### Key Decisions

- **Boot-receiver APK over flashing boot or vbmeta.** (session-settled: user-directed — chosen over flashing a modified boot image or vbmeta: no partition writes and fully reversible). Governs R1, R2, R17.
- **Build our own agent in-repo, reusing openmiko's `neuterd` with attribution.** (session-settled: user-directed — chosen over a separate fork or the unmodified upstream APK: one reproducible build and no external dependency). Governs R2.
- **Manual `/data/app` + `packages.xml` install.** (session-settled: user-directed — chosen over a normal-boot `pm` window or boot-image flash: factory mode has no package manager). Governs R6, R7, R9.
- **Target normal boot with the kiosk plus root ADB on USB and Wi-Fi.** (session-settled: user-directed — chosen over factory-mode-only or factory-mode-plus-Wi-Fi: persistence across reboots was the point). Governs R10, R11.
- **Wi-Fi as a DHCP client on the existing network.** (session-settled: user-directed — chosen over a stable IP, the Miko as its own AP, or leaving networking out: matches how the unit already joins Wi-Fi). Governs R11.
- **Python toolkit of focused scripts (build / install / verify / revert).** (session-settled: user-directed — chosen over one monolithic setup script: the four jobs are separately useful). Governs R13, R14, R16.
- **Commit a signed APK and keep the reproducible SDK build.** (session-settled: user-directed — chosen over either alone: normal use needs no SDK while source stays rebuildable). Governs R15, R16.

### Key Flows

- F1. One-time setup (factory-mode root session)
  - **Trigger:** A root ADB session in factory mode.
  - **Steps:** Confirm root; back up `/data/system/packages.xml` and `/data/system/users/0/package-restrictions.xml`; stage the APK on the device; place it under `/data/app`; authorize the host's ADB key; reboot to normal boot.
  - **Outcome:** The agent is registered and armed for the next normal boot.
  - **Covers:** R6, R7, R9, R12.

- F2. Normal boot
  - **Trigger:** A normal power-on.
  - **Steps:** Android reaches `BOOT_COMPLETED`; the receiver runs the root payload through `/system/bin/su`; `neuterd` enters init's mount namespace and shadows `/system/bin/reboot`; the agent sets the USB gadget to `mtp,adb` and starts adbd on TCP 5555; the host connects over USB and Wi-Fi.
  - **Outcome:** A root ADB shell is available on both transports with the kiosk running.
  - **Covers:** R1, R2, R3, R4, R5, R10, R11.

- F3. Revert
  - **Trigger:** The operator runs the revert script.
  - **Steps:** Stop the agent and `neuterd`; remove the agent's `/data/app` directory and any inserted `packages.xml` and `package-restrictions.xml` entries; restore the backups; reboot.
  - **Outcome:** The device returns to stock normal boot with no boot agent.
  - **Covers:** R14.

### Acceptance Examples

- AE1. **Watchdog defused.** Covers R2, R3, R5. Given a normal boot with ServiceExam running, when the agent has started, then `/system/bin/reboot` stays shadowed and adbd remains up despite ServiceExam's periodic checks.
- AE2. **USB config re-asserted.** Covers R4, R5. Given the agent set `sys.usb.config=mtp,adb` and ServiceExam later sets MTP-only, then the agent restores adb on the USB interface.
- AE3. **Receiver armed without a launcher.** Covers R7. Given the agent placed and registered but never launched, when the device boots normally, then `BOOT_COMPLETED` is delivered and the payload runs.
- AE4. **Kiosk preserved.** Covers R8. Given setup completed, when the device boots normally, then ServiceExam and MikoPlus remain installed and the kiosk reaches its normal screen.
- AE5. **No on-device authorization prompt.** Covers R12. Given a host that has never connected, when it connects over USB or Wi-Fi, then adb authorizes without an on-device confirmation.

### Scope Boundaries

- **Deferred for later:** an app or command that displays the device's DHCP address; surviving a Miko OTA that changes `su`, `reboot`, or ServiceExam; support for units other than the documented one.
- **Outside this work's identity:** anything touching Miko's servers, other people's units, or accounts; disabling or replacing the kiosk; making the device headless.
- **Deferred to Follow-Up Work:** `docs/method-factory-root.md:66-69` still tells the operator to disable the watchdog by renaming `/data/app/com.example.root.serviceexam-*` to `…-disabled`. That is the action `docs/mishap-recovery.md` records as having broken the kiosk, and `scripts/factory-root.sh` already supersedes it with the bind-mount shadow. Correcting the stale doc is a separate change; nothing in U4-U6 may follow that manual step.

### Dependencies / Assumptions

- The unit stays a `userdebug` build with a setuid, world-executable `/system/bin/su`, SELinux permissive, and `ro.debuggable=1`, so adbd runs as root on normal boot.
- The device is connected to a Wi-Fi network the host can also reach.
- Factory mode remains reachable through `scripts/factory-root.sh` for setup and recovery.
- Firmware backups under `firmware/` remain available for recovery.
- The target is the single documented unit profile in `docs/device-intel.md`.

### Sources / Research

- `recon/sources/miko3-adb-boot-agent/` — vendored research from `ne3d-4-steve/miko3-adb-boot-agent`: `neuterd.c`, `RootOps.java`, `BootReceiver.java`, `MainActivity.java`, `AndroidManifest.xml`, `install-permanent-adb.sh`.
- `docs/device-intel.md` — confirmed device posture (setuid `su`, permissive SELinux, verity enforcing, watchdog behavior).
- `docs/method-factory-root.md` — the factory-mode entry and its limits.
- `docs/kiosk-recovery-fix.md`, `docs/mishap-recovery.md` — the manual `/data/app` + `packages.xml` install, and the warning that ServiceExam is the installer of record.
- `scripts/factory-root.sh`, `scripts/brom-probe.sh` — the existing session-only neuter and the META `FACTFACT` route.
- Device-verified during this brainstorm: `/init.usb.configfs.rc` triggers for `sys.usb.config=adb` and `mtp,adb`; per-package `stopped="true"` in `/data/system/users/0/package-restrictions.xml`; `ro.adb.secure` unset with no `/data/misc/adb/adb_keys`; `/system/bin/reboot` shadowed only for the current factory-mode session.

---

## Planning Contract

**Product Contract preservation:** unchanged scope; restructured in place — the four deferred questions are resolved by KTD1–KTD5, and the boot-sequence diagram moved into High-Level Technical Design.

### Key Technical Decisions

- KTD1. **Reuse `neuterd` verbatim as the reboot-shadow mechanism.** The freestanding arm64 daemon `setns`es into init's global mount namespace and keeps a no-op bind-mounted over `/system/bin/reboot`, self-healing every 5 s. The payload starts it before adbd; an app-side mount would be invisible to ServiceExam, which is a zygote child in the global namespace. (session-settled: user-directed — chosen over a separate fork or the unmodified upstream APK: one reproducible build and no external dependency.) Governs R2, R17.
- KTD2. **Set the adb USB combo only after `neuterd` is up, and never via `persist.sys.usb.config`.** `/init.usb.rc` applies `persist.sys.usb.config` to `sys.usb.config` at boot, and the `sys.usb.config=mtp,adb` trigger starts adbd — so persisting the adb combo would start adbd before the neuter exists and let the watchdog reboot the unit. Governs R4.
- KTD3. **A root shell watcher re-asserts the USB config and adbd liveness.** The payload starts a small `setsid` loop that restores `sys.usb.config=mtp,adb` when ServiceExam changes it and restarts adbd if it stops. A shell loop avoids writing a native property client. Governs R5.
- KTD4. **Register by placement, verify, then fall back to a verbatim entry.** Install writes the signed APK to `/data/app/com.miko3.bootagent-<suffix>==/base.apk` (owner `system:system`) and lets PackageManagerService register it on the next boot; a `stopped` flag in `package-restrictions.xml` is cleared directly only when the boot log shows the agent did not run. The fallback when registration does not occur is the `<package>` block insertion from `docs/kiosk-recovery-fix.md`, where the block is **extracted verbatim from the install-time backup** — never synthesized — because a `packages.xml` `<cert key=…>` holds signature bytes, not a digest of the certificate. Governs R6, R7. **(session-settled carrier: the `/data/app` + `packages.xml` Key Decision above. Conflict call-out: every documented success with hand-placement on this unit restored a package that was *already registered*, with its original block re-inserted verbatim; there is no recorded case of PMS registering a never-before-seen package from a hand-placed directory alone. Placement-then-verify remains the right order and the fallback stays, but the never-registered variant is unproven until the first normal boot proves it — U5's registration assertion is what settles it.)**
- KTD5. **Pre-authorize the host key in `/data/misc/adb/adb_keys`.** Install writes the host's `adbkey.pub` there (owner `system`, mode `0640`), so USB and TCP authorization do not depend on `ro.adb.secure` on normal boot. Governs R12.
- KTD6. **Pipe the payload to `/system/bin/su` on stdin.** This `su` has no `-c`, so `RootOps` execs `su` and writes the script to its stdin, matching the openmiko payload shape. Governs R1.
- KTD7. **adbd over TCP is `service.adb.tcp.port=5555` plus an unconditional `ctl.restart adbd`.** Setting the property alone only moves a *fresh* adbd onto TCP: if adbd is already running without it, nothing retries and TCP stays off for the whole boot. The restart runs after the neuter is up, so it cannot race the watchdog. Governs R3, R11.
- KTD8. **The build bootstraps its own toolchain and is skippable.** `build-bootagent.py` installs the Android command-line tools and `lld` when absent, then drives `javac` → `d8` → `aapt2` → `zipalign` → `apksigner` and compiles `neuterd`; the committed APK means normal use never runs it. Governs R15, R16.
- KTD9. **Revert removes only the agent.** It kills `neuterd` and the watcher, deletes the agent's `/data/app` directory, and removes the agent's `packages.xml` / `package-restrictions.xml` entries surgically. It restores a backup only when the surgical removal cannot leave the file coherent. Governs R14.
- KTD10. **Every device command names its transport.** The toolkit targets `adb -s <usb-serial>` or `adb -s <ip>:5555` explicitly rather than relying on the default device. Once Wi-Fi is up, `adb devices` lists two transports and an untargeted `adb shell` fails with "more than one device" — which reads as Wi-Fi DOWN while it is up, and can attribute the USB transport's answer to Wi-Fi. Governs R10, R11, R13.
- KTD11. **Verify asserts identity and liveness, not a proxy.** U5 asserts registration (`pm path com.miko3.bootagent`), the reboot shadow, and the `neuterd` and watcher processes as separate facts, and reports the adb authorization state. A missing boot log currently collapses "registered but payload failed" and "never registered" into one verdict, and a byte-size check on `/system/bin/reboot` cannot distinguish a shadow from a truncated real binary. Governs R13.

### High-Level Technical Design

The payload runs once per boot, in this order:

```mermaid
flowchart TB
  BC[BOOT_COMPLETED] --> RX[BootReceiver.onReceive]
  RX -->|goAsync, background thread| SU[exec /system/bin/su, payload on stdin]
  SU --> N1[materialize neuterd, setsid start]
  N1 --> N2{/system/bin/reboot shadowed?}
  N2 -->|no, retry every 5s| N1
  N2 -->|yes| P1[setprop service.adb.tcp.port 5555]
  P1 --> P2[setprop sys.usb.config mtp,adb]
  P2 --> PR[ctl.restart adbd - KTD7]
  PR --> P3[start watcher: re-assert usb config, restart adbd]
  P3 --> P4[settings put global stay_on_while_plugged_in 3]
```

Component and data-flow topology:

```mermaid
flowchart LR
  subgraph Host[Host Python toolkit]
    I[install]
    V[verify]
    Rv[revert]
  end
  subgraph Device[Factory-mode root session]
    D1["/data/app/com.miko3.bootagent-<suffix>==/base.apk"]
    D2["/data/misc/adb/adb_keys"]
    D3["/data/system/users/0/package-restrictions.xml"]
  end
  I --> D1
  I --> D2
  I --> D3
  subgraph Boot[Normal boot]
    Init[init] -->|BOOT_COMPLETED| Rec[BootReceiver]
    Rec --> Su["/system/bin/su"]
    Su --> N[neuterd]
    Su --> W[watcher]
    Su --> Adbd[adbd]
    N --> Reb["/system/bin/reboot shadowed"]
    W --> Usb["sys.usb.config=mtp,adb"]
  end
  V --> Boot
  Rv --> D1
```

Agent lifecycle across the toolkit's actions:

```mermaid
stateDiagram-v2
  [*] --> Absent
  Absent --> Placed: install writes /data/app
  Placed --> Registered: normal boot, PMS scan (KTD4)
  Registered --> Running: BOOT_COMPLETED payload runs
  Registered --> Stopped: PMS marks stopped
  Stopped --> Running: clear stopped, reboot (KTD4 fallback)
  Running --> Absent: revert removes package
  Placed --> Absent: revert
```

Revert ordering (KTD9). The order exists to keep the device out of the dangling-entry state, so it is a safety constraint rather than a preference:

```mermaid
flowchart TB
  R0[revert] --> R1{kill watcher + neuterd}
  R1 --> R2[remove agent entries from packages.xml / package-restrictions.xml]
  R2 --> R3[remove /data/app/com.miko3.bootagent-*]
  R3 --> R4[restore adb_keys to recorded pre-install state]
  R4 --> R5{backup present?}
  R5 -->|no| R6[hard error, stop]
  R5 -->|yes| R7[dumpsys ServiceExam + MikoPlus]
  R7 --> R8[reboot, capture transcript]
```

### Assumptions

- `ro.adb.secure` is unset or `0` on normal boot as it is in factory mode; KTD5 makes this non-load-bearing by pre-authorizing the key.
- Wi-Fi associates automatically on normal boot to a network the host can reach, so the TCP listener is addressable.
- A reboot issued from factory mode returns the unit to normal boot. **Unverified:** `scripts/factory-root.sh` makes no such claim, and nothing in the repo confirms that a warm reboot from a `ro.bootmode=factory` session leaves factory mode. U4's install and U5's `classify_boot` both depend on the transition, so the physical power cycle stays the declared fallback and is the one manual step.
- PackageManagerService registers an APK hand-placed under a conforming `/data/app/<pkg>-<suffix>==` directory on the next boot; KTD4 verifies this and carries the `packages.xml` fallback if it fails.

### Risks & Dependencies

- **Boot race.** If adbd ever starts before `neuterd` applies the shadow, ServiceExam reboots the unit. KTD2 exists to prevent this; the payload starts `neuterd` first and waits for the shadow before touching the USB config.
- **Registration uncertainty.** If PackageManagerService does not register the hand-placed APK, or marks it stopped, no adb comes up on the first normal boot and factory mode must be re-entered. KTD4's verification and `packages.xml` fallback are the mitigation.
- **System-state integrity.** `packages.xml` and `package-restrictions.xml` are load-bearing system databases; a malformed edit prevents boot, and a stale wholesale restore rolls back every entry PackageManagerService has written since install. Mitigation: the primary path never edits `packages.xml`; U6 removes the agent's own entries surgically (KTD9) and treats a missing backup as a hard error rather than a note. R9's backups plus the restore path in `docs/mishap-recovery.md` are the rollback.
- **Wholesale-restore cascade.** `docs/mishap-recovery.md` records the one documented breakage on this unit: deleting a `/data/app` directory whose `packages.xml` entry survived — or rewriting the file with a cleared/partial one — made the package manager purge all of `/data/app`, taking the updated MikoPlus and every `oat/` with it. This is why U6's directory removal and entry removal are ordered against each other deliberately, and why "no backup found" must stop rather than proceed.
- **Toolchain install.** KTD8 needs network access for the Android command-line tools and `lld`; without them only the committed APK is usable and R16 is unverified.
- **Hardware risk.** The unit is already modified and backed up under `firmware/`; a mis-written system-state file can prevent boot. The rollback ladder is: restore the backed-up system-state files (U6), then `scripts/restore-firmware.sh` from `firmware/`, then re-enter factory mode with `scripts/factory-root.sh`.

### System-Wide Impact

- The device's boot path gains a root-executing boot receiver; the unit's security posture is already permissive, so this widens convenience, not the trust boundary.
- ServiceExam and MikoPlus are deliberately untouched (R8); the kiosk and the OTA engine keep working.
- The agent's state is `persistent` for the unit's lifetime: it re-arms on every boot and is removed only by revert, so `packages.xml` and `/data/app` are the durable state a future OTA or factory reset would clear.
- If the agent fails to run, the failure is contained to normal boot — adbd simply stays off and factory mode remains the recovery entry, so a failure never strands the unit without a console.
- No partition is written (R17), so a firmware restore from `firmware/` remains the ultimate rollback.

---

## Implementation Units

### U1. Boot agent app source and root payload

- **Goal:** A minimal Android app whose `BOOT_COMPLETED` receiver runs the privileged payload.
- **Requirements:** R1, R2, R3, R4, R5, R17; realizes F2
- **Dependencies:** none
- **Files:** `bootagent/AndroidManifest.xml`, `bootagent/src/com/miko3/bootagent/BootReceiver.java`, `bootagent/src/com/miko3/bootagent/MainActivity.java`, `bootagent/src/com/miko3/bootagent/RootOps.java`
- **Approach:**
  1. Package `com.miko3.bootagent`; manifest declares a `RECEIVE_BOOT_COMPLETED` receiver for `BOOT_COMPLETED` and the QUICKBOOT actions, and a launcher activity kept only for parity.
  2. `BootReceiver` uses `goAsync()` and a background thread so the broadcast window is not a constraint.
  3. `RootOps` holds the payload as a string with a `@@NEUTERD_B64@@` placeholder, execs `/system/bin/su`, writes the payload to stdin, and surfaces a non-zero exit rather than swallowing it.
  4. The payload starts `neuterd` first, waits for the reboot shadow, then sets the adbd TCP property, sets the USB config, restarts adbd (KTD7), and starts the KTD3 watcher.
- **Patterns to follow:** `recon/sources/miko3-adb-boot-agent/src/com/openmiko/bootagent/` (`BootReceiver`, `MainActivity`, `RootOps`).
- **Test scenarios:**
  - Manifest declares the receiver for `BOOT_COMPLETED` (assert on the built APK in U3).
  - The payload string contains, in order, the neuterd start, the adbd TCP property, the USB config set, and the watcher start.
  - The payload restarts adbd unconditionally after setting the TCP property, rather than only on a not-running adbd (KTD7).
  - `RootOps` execs `su` and writes to stdin rather than passing a `-c` argument.
- **Verification:** source compiles under U3 and the manifest dump shows the receiver.

### U2. neuterd native daemon

- **Goal:** Vendor and build the arm64 reboot-shadow daemon.
- **Requirements:** R2, R17
- **Dependencies:** none
- **Files:** `bootagent/native/neuterd.c`, `bootagent/native/build-neuterd.sh`
- **Approach:** Copy `neuterd.c` from `recon/sources/miko3-adb-boot-agent/` unchanged, with an attribution header naming `ne3d-4-steve/miko3-adb-boot-agent`; build for `aarch64` with `clang` + `ld.lld`; the committed binary is embedded into the APK by U3.
- **Patterns to follow:** `recon/sources/miko3-adb-boot-agent/neuterd.c`; the openmiko `native/build-neuterd.sh` invocation.
- **Test scenarios:**
  - The built binary is an aarch64 ELF.
  - `Test expectation: none` for the daemon's runtime behavior at unit level — its effect is proved on-device in U5.
- **Verification:** `file` reports aarch64; U5 confirms the shadow on the device.

### U3. Reproducible build and committed APK

- **Goal:** One command builds and signs the APK; a signed APK is committed.
- **Requirements:** R15, R16
- **Dependencies:** U1, U2
- **Files:** `scripts/build-bootagent.py`, `bootagent/miko3-bootagent.keystore`, `bootagent/miko3-bootagent.apk`, `bootagent/README.md`, `scripts/tests/test_build_bootagent.py`
- **Approach:**
  1. Bootstrap the Android command-line tools, `platforms;android-28`, build-tools, `lld`, and `zip` when missing, with an actionable error if installation cannot proceed (KTD8). `zip` is an external binary the build shells out to, so it belongs in the same precondition check as the SDK components.
  2. Compile `neuterd`, base64 it, and inject it into `RootOps.java`.
  3. `javac` → `d8` → `aapt2 link` → add `classes.dex` → `zipalign` → `apksigner`, generating the debug keystore on first run.
  4. Commit the resulting APK and keystore.
- **Patterns to follow:** `recon/sources/miko3-adb-boot-agent/install-permanent-adb.sh` (no-gradle build), `scripts/aoa-inject.py` (module docstring and argparse).
- **Test scenarios:**
  - Build with the toolchain present produces an APK that `apksigner verify` accepts.
  - `aapt2 dump badging` shows package `com.miko3.bootagent` and the boot receiver.
  - A missing toolchain produces a clear, actionable failure rather than a stack trace.
  - The committed APK is byte-stable across a rebuild given the same keystore.
- **Verification:** `apksigner verify` and `aapt2 dump badging` pass; the committed APK matches the built one.

### U4. Install from a factory-mode root session

- **Goal:** Register and arm the agent without a package manager.
- **Requirements:** R6, R7, R8, R9, R12; realizes F1; covers AE3, AE4, AE5
- **Dependencies:** U3
- **Files:** `scripts/install-persistent-adb.py`, `scripts/tests/test_install_persistent_adb.py`
- **Approach:**
  1. Refuse to run unless the targeted session's `id -u` is `0` and `ro.bootmode` is `factory`.
  2. Back up every system-state file the install writes or replaces — `/data/system/packages.xml`, `/data/system/users/0/package-restrictions.xml`, and `/data/misc/adb/adb_keys` — to a timestamped directory on the host and on the device (R9). Record `adb_keys` as *absent* when it does not exist, so revert can restore absence rather than leaving the host key behind.
  3. Push the committed APK and place it at `/data/app/com.miko3.bootagent-<suffix>==/base.apk`, owned `system:system`, directory `0755`, file `0644`.
  4. Write the host `adbkey.pub` to `/data/misc/adb/adb_keys`, owned `system:system`, mode `0640` (KTD5).
  5. Reboot to normal boot.
  6. Provide the KTD4 fallback as a reachable step, not a library: a post-boot arm path that reads the boot log, clears a `stopped` flag for the package when the receiver was registered but suppressed, and — only when registration itself failed — inserts the agent's `<package>` block **extracted verbatim from the step-2 backup** into `packages.xml` (KTD4). The fallback must never synthesize an entry.
- **Patterns to follow:** `docs/kiosk-recovery-fix.md`; `scripts/factory-root.sh` (device-root preconditions, `say`-style output).
- **Test scenarios:**
  - Aborts when the session is not root or not factory mode.
  - Creates backups of all three system-state files before any device write, and records `adb_keys` absence when the file is missing.
  - Places the APK with the expected owner and mode.
  - Writes `adb_keys` as `system` `0640`.
  - Re-running install is idempotent and does not duplicate entries.
  - The fallback entry is extracted verbatim from the backup and is byte-identical to the backup's block, never a synthesized entry with a digest in place of signature bytes.
  - Never touches `com.example.root.serviceexam` or `com.miko.mikoplus` (Covers AE4).
- **Verification:** after the reboot, `pm path com.miko3.bootagent` resolves and the boot log exists.

### U5. Verify both transports

- **Goal:** Report which transports are up and whether the agent is active.
- **Requirements:** R10, R11, R13; realizes F2; covers AE1, AE2, AE5
- **Dependencies:** U4
- **Files:** `scripts/verify-persistent-adb.py`, `scripts/tests/test_verify_persistent_adb.py`
- **Approach:**
  1. Gate on preconditions before reporting anything: a connected device, and the agent installed by U4. With no device attached the current shape reads empty properties as "still booting" and misreports a missing unit as a boot in progress.
  2. Detect normal boot (`sys.boot_completed=1`, `ro.bootmode` not `factory`). In factory mode say so, and state that factory mode has no Wi-Fi rather than reporting Wi-Fi as "not on the network".
  3. Report the adb authorization state explicitly (`device` vs `unauthorized` vs no transport, or the interface triplet via `aoa-inject.py --ifaces`), so a failed KTD5 key pre-authorization is distinguishable from a payload that never ran. Without this, AE5 has no observable signal.
  4. Assert registration separately from execution: `pm path com.miko3.bootagent` resolves, and the boot log is fresh against `/proc/uptime` rather than merely present. "Registered but payload failed" and "never registered" are different failures and must not collapse into one verdict.
  5. Over USB, target the USB serial (KTD10) and check `id -u` is `0`, `init.svc.adbd` is `running`, and `sys.usb.config` contains `adb`. Read the first four bytes of `/system/bin/reboot` for ELF magic rather than the byte-size heuristic (KTD11).
  6. Assert the `neuterd` process and the watcher process are alive, as facts separate from the shadow. The shadow is a bind mount that outlives the daemon for the rest of the boot, so "reboot is shadowed" does not imply the re-assertion R5 asks for is running.
  7. Read the `wlan0` address, `adb connect <ip>:5555`, then target `<ip>:5555` explicitly (KTD10) and re-check root.
  8. Print a per-transport verdict (USB, Wi-Fi) plus the neuter, registration, and authorization states, and write the verdict as JSON to `recon/captures/verify-persistent-adb-<utc>.json`.
- **Execution note:** AE2 is a behaviour, not a state — prove it by perturbation (set `sys.usb.config` to MTP-only, wait past the watcher's interval, re-read) rather than by reading the current value, which cannot show the re-assertion ever happened.
- **Patterns to follow:** `scripts/miko-detect.sh` (plain-English verdict, UTC-stamped header), `scripts/aoa-inject.py --ifaces` (interface-triplet check preferred over parsing `adb devices`), `scripts/restore-kiosk.sh` (root-precondition gate).
- **Test scenarios:**
  - Reports USB up / TCP up independently, and both up.
  - With both transports present, the Wi-Fi check reads UP — the regression that an untargeted `adb shell` produces, where Wi-Fi reads DOWN while it is up (KTD10).
  - With only TCP reachable, the Wi-Fi check does not report USB's answer as Wi-Fi's.
  - With no device attached, reports the missing device rather than a boot in progress.
  - Detects a non-root shell and reports it as failure.
  - Detects factory mode and says so, and names factory mode — not "not on Wi-Fi" — as the reason Wi-Fi is unavailable.
  - Reports `unauthorized` distinctly from a missing adbd, so a failed KTD5 pre-authorization is visible (Covers AE5).
  - Distinguishes a registered-but-not-run agent from a never-registered one.
  - Covers AE1: reports the reboot shadow present, and separately reports the `neuterd` and watcher processes running.
  - Covers AE2: after the perturbation, reports adb restored on the USB interface.
- **Verification:** the verdict names each transport as up or down with its evidence line, distinguishes registration from execution, and its JSON lands in `recon/captures/`.

### U6. Revert

- **Goal:** Remove the agent and restore the pre-install state.
- **Requirements:** R14; realizes F3
- **Dependencies:** U4
- **Files:** `scripts/revert-persistent-adb.py`, `scripts/tests/test_revert_persistent-adb.py`
- **Approach:**
  1. Kill the watcher and `neuterd`, targeting the device's transport explicitly (KTD10).
  2. Remove the agent's own entries from `packages.xml` and `package-restrictions.xml` surgically, using the string-level helpers that already exist for the purpose. Leave every other entry untouched — including everything PackageManagerService has written since install.
  3. Remove `/data/app/com.miko3.bootagent-*`, ordered against step 2 so the device never sits in the state where a `packages.xml` entry survives its directory. That state is what `docs/mishap-recovery.md` records as feeding the package-manager purge and the reinstall crash.
  4. Restore `/data/misc/adb/adb_keys` to its recorded pre-install state — delete the file when U4 recorded it absent. A revert that leaves the host key behind has not restored the pre-install state (R14).
  5. Treat a missing backup as a hard error, not a note. The current shape prints a note and proceeds, leaving the removal unverified on the one path where it matters.
  6. Re-assert the preserved state on the device: `dumpsys package` for ServiceExam and MikoPlus, as `scripts/restore-kiosk.sh` does, so R8/AE4 are provable rather than only reviewable.
  7. Reboot, and capture the removal transcript to `recon/captures/`.
- **Patterns to follow:** `scripts/restore-kiosk.sh` (root-precondition gate, targeted restore, no collateral removal, positive re-assertion of the preserved apps).
- **Test scenarios:**
  - Removes only the agent package's entries; ServiceExam and MikoPlus are untouched — asserted against the emitted command list, not only against the source text, and mirroring the `PROTECTED` guard test that `test_install_persistent_adb.py` already carries.
  - No emitted command removes or renames a `/data/app/com.example.root.serviceexam-*` or `/data/app/com.miko.mikoplus-*` path (Covers AE4).
  - Removes the agent's `packages.xml` and `package-restrictions.xml` entries via the surgical helpers, and leaves unrelated entries byte-identical.
  - Never writes a backup file over the live `packages.xml` when surgical removal succeeded.
  - Errors out when no backup is present rather than printing a note and continuing.
  - Restores `adb_keys` to absence when U4 recorded it absent, and to its backed-up content when it existed.
  - Is idempotent when the agent was never installed.
- **Verification:** after the reboot the agent is absent and normal boot has no adb, with the kiosk working. Note that "no adb after a normal boot" is also what a *correct* post-revert boot looks like once ServiceExam drives the USB config, so absence of adb is the expected result here and not a failure of U5's checks.

---

## Verification Contract

The repo has no test runner; the toolkit's pure logic is covered by stdlib `unittest`, and the device behavior is proved by the verify script on the unit.

- **Unit tests:** `python3 -m unittest discover scripts/tests`.
  - The toolkit scripts are not importable by name (hyphenated filenames), so tests load them through `importlib.util.spec_from_file_location`, following the `load()` helper in `scripts/tests/test_install_persistent_adb.py`.
  - Unit tests stub the module-level `sh`/`adb` seam with `unittest.mock` and must never invoke a real device. Tests for the verify script patch `read_device_state` and `tcp_root`, since the composition bug the transport targeting fixes is only reachable through them.
  - Assertions on the protected apps are made against the emitted command list, not only against source text.
- **Build proof:** `python3 scripts/build-bootagent.py` then `apksigner verify bootagent/miko3-bootagent.apk` and `aapt2 dump badging bootagent/miko3-bootagent.apk`.
- **Device proof (the decisive check):** `python3 scripts/verify-persistent-adb.py` after a normal boot reports USB up and Wi-Fi up, `id -u` is `0` on both, the agent registered, and the reboot shadow plus the `neuterd` and watcher processes present.
- **Revert proof:** `python3 scripts/revert-persistent-adb.py` followed by a normal boot shows the agent absent and no adb, with ServiceExam and MikoPlus still installed at their expected versions.

---

## Definition of Done

- R1–R17 are satisfied and the Verify Contract's device proof passes on the unit.
- The committed APK is present and `apksigner verify` passes on it.
- `scripts/verify-persistent-adb.py` reports both transports up after a normal boot with the kiosk running.
- The kiosk still boots and ServiceExam/MikoPlus are intact (AE4).
- `scripts/revert-persistent-adb.py` returns the unit to stock normal boot.
- Abandoned-attempt code and temporary device state are removed; no stray `neuterd` or watcher process, no leftover `/data/local/tmp` staging files.
- The working tree carries no scratch unrelated to the units: the cosmetic `bootagent/native/build-neuterd.sh` diff is either folded into U2 or reverted, and editor swap files are gone.
- Every unit's declared test scenarios are actually implemented, not merely listed — U2's aarch64 ELF assertion, U3's badging and rebuild-stability checks, and the new U5/U6 files included.
