# Findings — Miko 3

Working knowledge base. Primary sources:

- **mgdproductions security blog** — "Video Calling Vulnerabilities in Miko Smart Kid Robots"
  <https://blog.mgdproductions.com/miko-robots-vulnerabilities/>
  (Blocked on the local network by a UniFi content filter; retrieved via off-network fetch.)
- **Reddit r/hacking thread** — "Miko 3 robot"
  <https://www.reddit.com/r/hacking/comments/1r7oxdm/miko_3_robot/>

> Status: partially populated from web-search summaries of the blog. Full
> verbatim text of both sources is being retrieved off-network; this file will
> be corrected/expanded from the primaries. Treat unconfirmed items as leads.

## Confirmed from the mgdproductions writeup (via search summaries)

- The robot runs a **userdebug** Android ROM — i.e. `ro.build.type=userdebug`.
  That build type ships with root available and app-debugging enabled.
- **ADB shell is root by default.** The researcher initially expected only a
  `user`-level shell but found ADB gave root without any extra step.
- **Bootloader unlock via fastboot requires pressing volume-up to confirm** — the
  standard AOSP unlock-confirmation prompt. Notably, the volume-up button on the
  robot's head did **not** trigger the confirmation, so the physical unlock path
  was not straightforward on the unit tested.
- Root alone is not a remote danger — it needs physical access (which we have).
- The robots phone home to a central domain. A subdomain scan turned up a
  **simulator dashboard** behind username/password. The disclosed issues were
  around **video calling** between units.
- **Vendor response:** Miko said the researcher's unit was an *older hardware
  revision* whose userdebug ROM exposed ADB root, implying newer revisions may be
  locked down (`user` build, ADB off). So our unit's build type is the first
  thing to determine.

## Open questions (to answer from our own unit)

- [ ] Exact SoC/chipset, RAM, eMMC size — not in public sources; get from
      `getprop`, `/proc/cpuinfo`, teardown markings.
- [ ] `ro.build.type` on *our* unit: userdebug (jackpot) or user?
- [ ] Does the internal micro USB expose ADB, fastboot, a UART, or is it
      power/data-only? (Answered by the power-on USB diff.)
- [ ] Bootloader lock state: `fastboot getvar unlocked`.
- [ ] Android version / build fingerprint.
- [ ] Partition layout and filesystem.

## Implications for our approach

1. If ADB comes up as **root** on power-on → we're essentially done for a root
   shell; pivot straight to dumping partitions and persistence.
2. If ADB is present but **non-root / user build** → newer revision; look at the
   bootloader unlock path and/or a UART console.
3. The head volume-up button reportedly does not confirm fastboot unlock — so if
   we go the fastboot route, we may need to find the real confirm input (another
   button, a test pad, or the actual vol-up line on the board).

## 2026-09-11 — power-on + hotplug test: internal micro USB shows NO data path

Powered the unit on with the internal micro USB cabled to the Mac. Nothing
enumerated: no new `/dev/cu.*` node, no `adb` device, no `fastboot` device, no
new USB VID/PID.

Ran a controlled unplug/replug with timestamped snapshots
(`recon/captures/hotplug-*.txt`) and a full macOS USB-stack log dump across the
window. **Zero USB events** were logged on either unplug or replug.

Conclusion: the host receives **no USB data-line signal** from this cable+port.
On a live host controller, a plug event always logs at least a connect/reset even
when enumeration fails — so the absence of *any* event means the data lines are
not electrically reaching the Mac. Candidate causes, in order:

1. **Charge-only micro-USB cable** (D+/D- not wired). Most common.
2. Port's data lines not connected to the host in normal Android boot (some boards
   only bring USB-device up in a loader/fastboot mode).
3. Wrong connector — this one may be power/factory-only if there are others.

### Next actions
- [ ] **Cable sanity check**: same cable Mac↔known Android phone. Phone must show
      in `adb devices` (even as `unauthorized`). If it doesn't → charge-only cable.
- [ ] If cable is good: try entering bootloader/loader mode via a button-hold at
      power-on (combo TBD from primary sources) and re-watch USB.
- [ ] Inspect the board for other connectors / UART test pads.

## 2026-09-11 (update) — primary source retrieved; key corrections

Full mgdproductions writeup retrieved off-network. Raw text + digest archived in
`recon/sources/`. Corrections and additions to the above:

- **SoC is MediaTek, not Rockchip.** The writeup calls it a "vulnerable mediatek
  chip that should be able to be unlocked with tools like mtkclient" — though the
  author's mtkclient attempt errored out. Exact model/RAM/storage: still unknown.
- **Two internal USB ports.** External port = charge-only. A *hidden* internal
  micro-USB (reached by removing bottom screws and lifting the top) is the data
  port that gave ADB root.
- **Our unit:** cabled to the hidden data port, powered on and booted, yet the
  host logged **zero** USB events across a controlled unplug/replug. That is
  consistent with a charge-only cable (D+/D- not wired) or data lines not reaching
  the host — NOT with a live-but-locked device, which would still log connect/reset.

### Revised route options (in priority order)

1. **Rule out the cable first** — same cable Mac↔known Android phone; it must at
   least log a USB event / show in `adb devices`. Until this passes, every other
   test is ambiguous.
2. **MediaTek BROM / preloader path (mtkclient).** MTK SoCs enumerate as USB VID
   `0x0e8d` in BROM mode at the very start of power-on (or held there via a test
   point / key), *independent of bootloader lock or Android build*. If the data
   port is wired, an mtkclient handshake during the power-on window should produce
   a USB event even on a hardened unit. This is the most promising route for a
   locked/newer revision. Needs `mtkclient` installed + libusb.
3. **Normal-boot ADB** — only works if this is an older userdebug revision. Given
   nothing enumerated, lower priority until the cable is cleared.
4. **fastboot unlock** — blocked per the writeup by a volume-up confirm that the
   head button doesn't trigger; parked.

## 2026-09-11 (later) — what actually works on this unit, measured

**Input path: AOAv2 HID over the existing micro-USB, no OTG adapter.**
`scripts/aoa-inject.sh` negotiates AOA (`GET_PROTOCOL` = 2) and registers HID on
the control endpoint. Both channels reach the display:

- keyboard chords land (`meta+n` opens the shade on every attempt, before *and*
  after the boot animation — the "only during boot animation" assumption was
  wrong for this firmware)
- the relative pointer is rendered (a cursor dot traces a rectangle during
  `--wiggle`)
- AOA gives exactly **two HID slots**. If the keyboard is registered last it
  survives; registering the mouse afterwards is what produced the earlier
  `Errno 32 Pipe error` wall. The scripts now register mouse first, keyboard
  second, then confirm with an empty report.

**What the UI exposes.**

- Shade contents: Wi-Fi, Bluetooth, Do Not Disturb, Lock rotation, Battery,
  Airplane mode, then "No notifications". Clock/date top-left, Wi-Fi bars
  top-right. **There is no settings cog at all**, so the upstream
  "pull the shade, tap the gear" step cannot be followed literally.
- Focus walking works and is visible: `tab` lands on tiles (`tab` x3 → DND),
  arrows move between tiles and wrap. There is one extra **unlabelled focus stop
  under Airplane mode**; `enter` there does nothing.
- Tiles respond to a click/`enter`. A stationary **long-press does nothing** on
  them (tested by hand and over HID).
- Typed characters only land when an edit field already holds focus — proved by
  `text:develop` appearing in the "enter network name" box on the Wi-Fi page.
  With nothing focused, keystrokes are dropped. `meta+a` does nothing (no app
  drawer on this launcher).
- Wi-Fi setup is not a shortcut: after connecting it lands on the "couldn't get
  unlock code" parental-gate page.

**AOA string handshake is not enough on this firmware.** `--ads AdsDebug` and
`--ads Ace` both complete (protocol 2, strings + `START` accepted) and the unit
immediately re-enumerates under Google's VID as PID `0x2d00`, i.e. accessory mode
really engages — but `adb devices` stays empty and the interface triplet stays
`ff/ff/00`. A host-side port reset (`--reset`) drops it back to PID `0x2008`
without rebooting the unit, so no cable walk is needed between attempts.

**Machine-readable success check.** Read the interface triplet instead of parsing
`adb devices`: `scripts/aoa-inject.sh --ifaces`. Locked-down state is one
interface `ff/ff/00`; enabling USB debugging should show `ff/42/01`. This works
even before any RSA authorization, which `adb devices` does not. Also confirmed:
nothing listens on TCP 5555 anywhere on the LAN while USB debugging is off, so
there is no wireless-adb back door to aim at instead.

**Boot ladder, measured.** Preloader (`MT65xx Preloader`, PID `0x2000`) is on the bus
~2.6 s per appearance (measured 2.59 / 2.59 / 2.68 s in
`recon/captures/boot-modes-20260911T194341Z.txt`) and returns several times during
boot (same capture: t+26.4s, t+38.2s, t+54.3s). Its CDC-ACM node is visible for a
shorter slice inside that window — about 1.1 s (t+37225ms to t+38307ms in
`recon/captures/brom-probe-215904.log`) — so size a handshake attempt against the node,
not the stage. BROM is PID `0x0003`. Captures in `recon/captures/boot-modes-*.txt`.

## 2026-09-11 (later still) — the working route to a real console: preloader META port

This is the breakthrough. Everything below is reproduced from
`recon/captures/brom-probe-*.log` and is stable across four consecutive boots.

**The MediaTek preloader on this unit exposes a text console, not just a bulk pipe.**

Measured ladder from a cold power-on (USB stays connected the whole time):

| Time | Bus state | What it is |
|------|-----------|------------|
| t+0 s | PID `0x2008`, name `MIKO3`, iface `ff/ff/00` | Android already up (previous boot) |
| t+9…27 s | bus silent | power-off / reset gap |
| ~t+25 s | PID `0x2000`, name `MT65xx Preloader`, fresh node `/dev/cu.usbmodem2100` | preloader, on the bus ~2.6 s; its serial node is usable for ~1.1 s of that |
| ~t+44 s | PID `0x201c`, name `Android`, iface `ff/42/03` | fastboot gadget, after we write `FASTBOOT` |

The handshake on `/dev/cu.usbmodem2100 @ 115200`:

- write `a0 0a 50 05`, read `52 45 41 44` = ASCII **`READ`**, ~300 ms after the stage appears
- the port then streams ASCII `READY` repeatedly — MediaTek's META handshake prompt
- writing a mode name right after `READY` is honoured: `FASTBOOT` moved the unit into
  fastboot on the next enumeration (PID `0x201c`, interface `ff/42/03`)
- `fastboot devices` then lists `MIKO3250XXM3Q0636CB`

So the chain is: **power-cycle → catch the ~2.6 s preloader stage (≈1 s of live serial
node) → talk META over its CDC-ACM node → name the mode we want.** No buttons, no OTG
adapter, no UI navigation.
`scripts/brom-probe.py` does the watch-and-write; `scripts/fastboot-once.sh` wraps the
whole cycle and issues the first fastboot read itself.

**Why mtkclient reported `Handshake failed` on a link that demonstrably works.** Its
`tools/mtkclient/mtkclient/Library/Port.py` — in `run_handshake()` — writes the SYNC one byte at a time and requires each
byte echoed back inverted (`0xa0` → `0x5f`, …), which is classic BROM behaviour. This
chip answers the whole SYNC at once with ASCII `READ` instead, so the per-byte
comparison never matches and it gives up after five retries — while the port itself is
perfectly responsive. `tools/mtkclient/mtkclient/Library/meta.py` *does* match this
behaviour (read until `READY`, then write a mode string), which is the shape our probe
copies. Related: pass a fixed port (`--serialport /dev/cu.usbmodem2100`) rather than
`DETECT`. The only persistent ACM node on this Mac belongs to the LG monitor
(`usbmodem208NTXR9D2662`, see `docs/recon.md`), and detection can claim that one instead
of the preloader's node. The booted Android gadget itself exposes no serial node at all —
just one vendor interface, `ff/ff/00`.

**Fastboot on this unit is minimal but not dead.** Named getvars answer one after another
in a single pass: `secure: yes`, `product: tb8168p1_64_bsp`, `version: 0.5`,
`version-bootloader: tb8168p1_64_bsp-a95430d-20230610183654-20230610224324`,
`serialno: MIKO3250XXM3Q0636CB`, `hw-revision: 0`, `battery-voltage: 4003mV`,
`max-download-size: 0x8000000`, `partition-size:boot: 1000000`,
`partition-size:system: 211008000` (the `a95430d` inside `version-bootloader` is the
bootloader's own build tag, not a git ref in this repo). `getvar all` and `getvar unlocked` instead return
`(remote: 'unknown command')`, `partition-size:vendor` and the keys after it time out at
15 s, and `fastboot -s serial:<n> getvar …` sits at `< waiting for any device >` even
though plain `fastboot devices` lists the unit. Practical rule: ask for the specific keys
you need, in one bounded pass — which is exactly what `scripts/fastboot-once.sh` does.

**Also confirmed:** the persistent ACM node (`usbmodem208NTXR9D2662`, the LG monitor's) is
not a console — silent at 115200, 921600, 1500000 and 3000000 baud, with and without a
CR/LF. And the AOAv2 model-string
trick (`AdsDebug` / `Ace`) still only flips the unit into accessory mode; `adb devices`
stayed empty through a replug and a reboot.
