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
