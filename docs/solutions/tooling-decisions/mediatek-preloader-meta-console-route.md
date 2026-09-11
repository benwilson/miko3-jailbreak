---
title: "Reach a locked Miko 3 through the MediaTek preloader's META serial port, not through its UI"
module: "MediaTek USB console access on locked-down Miko 3"
date: "2026-09-11"
problem_type: tooling_decision
category: tooling-decisions
component: tooling
severity: high
related_components:
  - "infrastructure"
  - "development_workflow"
tags:
  - "aoav2"
  - "mtkclient"
  - "mediatek-preloader"
  - "fastboot"
  - "cdc-acm"
  - "locked-down-kiosk"
  - "serial-port-selection"
applies_when:
  - "A locked-down Android 9 kiosk unit exposes no settings route and adb never comes up"
  - "A MediaTek unit enumerates briefly as MT65xx Preloader (PID 0x2000) during boot"
  - "A host-side console is needed with only the existing micro-USB cable and no OTG adapter"
symptoms:
  - "adb devices stays empty after the AOAv2 AdsDebug/Ace accessory-mode flip, across a replug and a reboot"
  - "Nothing answers on TCP 5555 anywhere on the LAN"
  - "The only always-present CDC-ACM node on the host belongs to an LG monitor and is silent at 115200, 921600, 1500000 and 3000000 baud"
  - "Kiosk shade shows six tiles, clock/date and Wi-Fi bars with no settings gear"
  - "mtkclient reports Status: Handshake failed on a link that is demonstrably responsive"
root_cause: missing_tooling
resolution_type: tooling_addition
---

## Context

The Miko 3 is locked down in a way that defeats the usual "plug in a keyboard and walk the Settings app" recipe. Observed on this unit: the notification shade contains six tiles plus a clock/date and Wi-Fi bars and has **no settings gear at all**, so the documented "pull the shade, tap the gear" step has nothing to hit. There is no app drawer (`Win+A` does nothing), a long-press on a tile does nothing, and typed characters only land when an edit field already holds focus.

The AOAv2 model-string trick (`Model="AdsDebug"` / `"Ace"`) does flip the unit into accessory mode — VID `0x0e8d` → `0x18d1`, PID `0x2d00`, visible in `scripts/miko-detect.sh` output — but `adb devices` stayed empty across a replug and a reboot, and nothing on the LAN answered TCP 5555, so there is no wireless-adb back door either. The booted gadget exposes no serial node at all, just one vendor interface; the persistent `usbmodem` node on this Mac belongs to the LG monitor (`docs/recon.md`) and produced no bytes at 115200, 921600, 1500000 or 3000000 baud.

Meanwhile mtkclient looked like the way out but kept printing `Status: Handshake failed, retrying...` even though one restart log recorded 135 successful *detections* of the preloader — the failure was in the handshake step, not in catching the window. That is the header note of `scripts/brom-probe.py`.

(session history) The same conclusion was missing in earlier sessions precisely because the diagnosis was never written down: the prior run ended at a WebFetch of the mtkclient usage notes with no recorded outcome, and the shade/settings manipulation attempts it made first are documented only as failures. It also noted that recon context is not reliably inherited between sessions, so `recon/captures/` and `recon/sources` are worth restating at the start of a new session rather than assuming a successor will find them.

## Guidance

Drive the unit through the MediaTek **preloader stage's CDC-ACM port**, which speaks MediaTek's META text protocol, instead of trying to reach the Android UI or the adb interface. The sequence that worked:

1. Keep the micro-USB connected and power-cycle the unit; do not touch the buttons. On this unit the buttons do not hold download mode — they only let it boot normally.
2. Watch USB enumeration at a short interval and wait for the preloader stage: PID `0x2000`, product name `MT65xx Preloader`. Observed several times per boot (~t+26 s, ~t+38 s, ~t+54 s), each appearance about **2.6 s** wide with its serial node live for roughly **1.1 s** of that, so a missed window is retried in the same power cycle rather than costing a reboot.
3. Diff `/dev/cu.usb*` against the baseline taken while Android was up, and take the *fresh* node — `/dev/cu.usbmodem2100` here. The only always-present node in that baseline is the LG monitor's `usbmodem208NTXR9D2662`, so a diff is what separates the device's node from the host's. Confirm by VID/PID rather than by the `usbmodem` prefix, because that monitor claims a `usbmodem` CDC-ACM node on the same host (`docs/recon.md`).
4. At 115200 baud, write the download-mode SYNC `a0 0a 50 05`, read the reply (ASCII `READ`, about 300 ms later on this chip), keep reading until repeated `READY` appears, then write the desired mode name immediately — `FASTBOOT` handed this unit to fastboot.

Use a retry loop with short reads rather than one long-blocking open, because a single 1.5 s timeout can consume the entire window. That is what `serial_hunt()` in `scripts/brom-probe.py` does, and it is tried **before** any bulk or ep0 attempt in `attempt()`:

```python
def attempt(dev, n, baseline, metamode=None):
    """One handshake try. Serial first — it is the interface the preloader actually
    answers on and it is fast — then the bulk pair, then ep0."""
```

Issue the fastboot reads inside the same short-lived pass. Observed: named getvars answer in one session after another (`secure: yes`, `product: tb8168p1_64_bsp`, `serialno: MIKO3250XXM3Q0636CB`, `max-download-size: 0x8000000`, `partition-size:boot: 1000000`), while `getvar all` and `getvar unlocked` come back `(remote: 'unknown command')`, `partition-size:vendor` and later keys time out around 15 s, and `fastboot -s serial:<n> getvar …` sits at `<waiting for any device>` while plain `fastboot devices` still lists the unit. Read the keys you actually need, in one bounded pass. `scripts/fastboot-once.sh` encodes that ordering — probe in the background, wait for PID `0x201c`, then read immediately:

```bash
if [ "$seen" = "0x201c" ]; then
  say "fastboot interface up after ${SECONDS}s — reading immediately"
  timeout 30 fastboot devices 2>&1 | tee -a "$log"
  timeout 30 fastboot getvar all 2>&1 | tee -a "$log"
```

When mtkclient is used at all, pass a fixed `--serialport /dev/cu.usbmodem2100` rather than `DETECT`, because the LG monitor's ACM node is present most of the time and detection can pick that one instead.

The reason mtkclient's own handshake fails on a live link is visible in `tools/mtkclient/mtkclient/Library/Port.py`: `run_handshake()` writes the SYNC byte-by-byte and requires each byte echoed back inverted (`echo[0] != (~byte & 0xFF)` raises `Echo mismatch`), which is classic BROM behaviour. This chip answers the whole SYNC at once with ASCII `READ`, so the per-byte comparison never matches and it gives up after its five retries while the port is perfectly responsive. `tools/mtkclient/mtkclient/Library/meta.py` matches the real behaviour (read until `READY`, then write a mode string), and that is the shape `scripts/brom-probe.py` copies.

Separately, for anything that must be typed into the Android UI, AOAv2 HID over the control endpoint works with no OTG adapter, but AOAv2 gives exactly two HID slots and this firmware keeps only the last registered one alive. `scripts/aoa-inject.py` therefore registers the pointer first and the keyboard last, then confirms the keyboard with an empty report — see `register_both()` and `confirm_kbd()`. Registering in the other order produced `Errno 32 Pipe error` on subsequent keyboard sends.

## Why This Matters

The preloader window is the only point in this unit's boot where a host gets a real, bidirectional console without depending on the kiosk app cooperating. Everything else in the stack is gated: the adb interface is absent until USB debugging is on, the shade has no gear to open Settings, and the launcher is a single-purpose screen. Missing the preloader stage means falling back to keystroke ladders that can only reach tiles, and tile toggles do not get closer to a shell.

The window is genuinely short — measured `t+37221ms` to `t+39743ms` for the whole serial exchange in the capture below — so the ordering of attempts matters more than the content of any single attempt. Putting serial first is what turns a two-second window into a reliable handshake; the earlier ordering that tried bulk transfers first spent most of the window on timeouts (`Errno 60 Operation timed out`, `Errno 5 Input/Output Error`) and reached the serial port only after the node had disappeared.

Finally, the same measurement settles a question that otherwise costs a lot of re-investigation: mtkclient's `Handshake failed` on this hardware is a mismatch between its per-byte echo expectation and this chip's ASCII reply, not evidence that the device is unreachable or that the window was missed.

## When to Apply

- A Miko 3, or a similar MediaTek-based locked-down Android device, is plugged in over its internal data port and shows a single vendor interface `ff/ff/00` with adb empty — which `./scripts/aoa-inject.sh --ifaces` prints directly from the descriptor instead of relying on `adb devices` and its RSA-authorization dependency.
- mtkclient detects the MediaTek preloader but fails its handshake on a device whose port demonstrably replies.
- Fastboot-mode commands hang after the mode switch, or `-s serial:<n>` stops matching a device that plain `fastboot devices` still lists.
- Keystrokes must be injected into a kiosk UI that has no path to Developer options.

## Examples

Watch a manual power-cycle and switch modes in one bounded run:

```bash
$ ./scripts/brom-probe.sh --no-reset --meta FASTBOOT --duration 150
t+      0ms  baseline serial nodes with Android up: ['/dev/cu.usbmodem208NTXR9D2662', '/dev/tty.usbmodem208NTXR9D2662']
t+     13ms  stage: 0x2008 (android)
t+  27478ms  stage: none (absent)
t+  37221ms  stage: 0x2000 (preloader)
t+  37225ms    try1: fresh serial nodes -> ['/dev/cu.usbmodem2100']
t+  37520ms    try1: /dev/cu.usbmodem2100@115200 try1 read=52454144 ready=True
t+  37877ms    try1: stream=b'READYREADYREADYREADY'
t+  38307ms    try1: fresh serial nodes -> none
t+  39743ms    try1: serial_hunt 1 tries, last error: read failed: [Errno 6] Device not configured
t+  44781ms  stage: 0x201c (preloader)
```

Reading those lines: the preloader stage opened at `t+37221ms`, its fresh serial node appeared 4 ms later, the SYNC was answered `52454144` = `READ` at `t+37520ms`, `READY` was streaming by `t+37877ms`, the node was gone by `t+38307ms`, and the unit had re-enumerated as the fastboot gadget (`0x201c`, interface `ff/42/03`) by `t+44781ms`. Everything that mattered happened inside about 2.5 seconds — which is why the node is timed against the ~1.1 s it is actually visible rather than the ~2.6 s the stage is on the bus. The trailing `Errno 6` line is expected once the stage closes — the handshake had already succeeded. `scripts/miko-detect.sh` prints `-> MODE: unknown MediaTek PID 0x201c` for that last stage: its case list covers `0x0003`, the `0x2000/0x2001/0x2003` preloader family, the `0x2d0x` accessory range, the `0x4e1x–0x4e2x` adb-interface range and `0x2008`, but not `0x201c`.

Bundle the mode switch with the first fastboot read:

```bash
$ ./scripts/fastboot-once.sh 90
=== fastboot-once @ 2026-09-11T21:59:06Z, window 90s ===
t+27s  pid=0x2000
fastboot interface up after 45s — reading immediately
MIKO3250XXM3Q0636CB	 fastboot
getvar:all                                         FAILED (remote: 'unknown command')
```

Identify the current stage, and read the interface triplet that decides whether adb is even offered:

```bash
$ ./scripts/miko-detect.sh
MediaTek/Google VID device present: name='Android' VID=3725 PID=0x201c
fastboot: MIKO3250XXM3Q0636CB	 fastboot

$ ./scripts/aoa-inject.sh --ifaces
PID=0x201c configs=1
  iface 0: ff/42/03
```

With the unit booted normally, the same `--ifaces` read prints `iface 0: ff/ff/00` — one vendor interface, no adb interface — which is the machine signal that USB debugging is off, without waiting on `adb devices` and an RSA authorization that never arrives. The three triplet states that matter here: `ff/ff/00` vendor-only with adb absent, `ff/42/01` adb interface present, `ff/42/03` the fastboot gadget, where fastboot answers and adb does not. With a keyboard or the AOAv2 HID channel attached, `./scripts/aoa-inject.sh --ladder` still opens the shade and moves the rendered pointer; on this unit that is as far as the UI goes, which is why the preloader port is the route worth remembering.

## Related

- `docs/findings.md` — the measured boot ladder and protocol notes this doc summarizes
- `docs/gotchas.md` — HID-slot ordering, the interface-triplet check, Chrome WebUSB contention
- `scripts/brom-probe.py`, `scripts/brom-probe.sh`, `scripts/fastboot-once.sh`, `scripts/aoa-inject.py`, `scripts/miko-detect.sh`
