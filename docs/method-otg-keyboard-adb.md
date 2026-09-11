# Method: OTG keyboard -> settings shade -> persistent ADB (latest firmware)

Source: repo author's own r/hacking writeup (pasted into the session). Route for
the **latest firmware**, where the Privacy-Policy long-press -> Share escape is
blocked and OTG input is blocked by default, so timing matters.

## Why this instead of the BROM route
USB debugging ships OFF and the accessible buttons do not hold MediaTek download
mode (see docs/plan.md), so the mtkclient/BROM path stalled on macOS. This path
enables ADB from inside Android's UI using a USB keyboard over OTG, then makes
adbd survive reboots. Once adbd is up, the Mac gets a normal `adb` connection --
no BROM needed. `Win+N` = Android's Meta+N hardware-keyboard shortcut for the
notification shade, which is what makes this work.

## Hardware
- USB keyboard + micro-USB **OTG adapter** into the Miko's hidden data port
  (Miko acts as USB host). A scriptable HID device (Pi Pico / Rubber Ducky) is
  better for hitting the timing than a human on a keyboard.
- An **SD card** loaded with the boot-agent APK (below).

Both are optional on this unit. AOAv2 HID over the control endpoint delivers the
same keystrokes with the Mac as USB host and **no OTG adapter** — see
`scripts/aoa-inject.py` — so the keyboard kit is a fallback, not a prerequisite.

## Steps (verbatim from the author)
> For latest firmware with Privacy Policy long press -> Share blocked (and OTG
> input blocked by default), you have to time it correctly. Start miko up, wait
> for your keyboard to have power by jamming the caps lock until the light comes
> on, then jam win+N as described above, but you must do it during the boot up
> process when her eyes are doing the slot machine-like animation. Pull the
> notifications drawer down, hit the gear icon in the bottom right corner, now
> you're in settings. I don't think you need Developer Options enabled, but i did
> it anyways. What you definitely do need to do, is go to Storage with your SD
> card in and loaded with this APK (source on github: ne3d-4-steve/miko3-adb-boot-agent).
> That'll bypass the protections and keep adbd running after reboots while also
> bypassing the com.example.ServiceExam watchdog that forces a reboot if it sees
> adbd running.

## Distilled sequence
1. Insert the SD card (with the boot-agent APK) and connect keyboard via OTG.
2. Power the Miko on.
3. Jam **Caps Lock** until its LED lights = keyboard now has power/enumerated.
4. During the **"slot machine" eye animation** (boot window), jam **Win+N**
   (Meta+N) to open the notification shade.
5. Pull the notifications drawer down; tap the **gear icon (bottom-right)** ->
   Settings. On this unit there is no cog — see the shade contents below, so the
   in-shade route ends at the tiles and the preloader's META console is how a
   shell is actually reached.
6. (Optional, author did it anyway) enable Developer Options.
7. Settings -> **Storage** -> open the SD card -> install the
   **miko3-adb-boot-agent** APK.
8. The agent keeps **adbd** running across reboots and defeats the
   **com.example.ServiceExam** watchdog that would otherwise reboot the device
   when it detects adbd.

## Key artifact
- GitHub: `ne3d-4-steve/miko3-adb-boot-agent` (APK source). Review before install
  -- see recon/sources/ for the fetched copy/analysis.

## Watchdog note
`com.example.ServiceExam` actively reboots the unit if it sees adbd running --
this is the "hardened newer revision" behavior. Any ADB-persistence approach must
neutralize this watchdog, which is exactly what the boot-agent claims to do.

## 2026-09-11 — what our unit's shade actually shows

The upstream writeup assumes a gear icon in the bottom-right of the shade. On this
unit it is not there. Confirmed contents of the opened shade:

- top-left: the shade handle, top-right: the date, plus the Wi-Fi indicator
- tiles: Wi-Fi, Bluetooth, Do Not Disturb (circle with a minus), Lock rotation,
  Battery, Airplane mode
- then "No notifications" — and **no settings cog at all**

So the "pull the shade, tap the cog" step cannot be followed literally here. What
the injections did establish:

1. **Keyboard codes reach the UI.** `meta+n` opened the shade on every attempt.
2. **The pointer is rendered.** A 2-second `--wiggle` shows a cursor dot tracing a
   rectangle, so pointer input does reach this display. A click outside the panel
   dismisses it, which is why a click in the extreme corner looks like "nothing
   happened" — inset from the homed edge before clicking.
3. **Typed letters are dropped on the home page.** There is no focused edit field,
   so type-to-search and Settings-search shortcuts do not apply.

### Input channels confirmed on this unit

| Channel | Result |
|---------|--------|
| Keyboard codes (`meta+n`, `tab`, `enter`, arrows) | Work — the shade opens on every attempt |
| Relative pointer (`move:*`, `click`) | Works — a cursor dot is visible and travels; a click outside the panel dismisses it |
| Typed letters on the home page | Dropped — nothing holds an edit-field focus, so type-to-search does not apply |
| Absolute touch (`tap:x,y`) | Descriptor registered; not yet distinguished from the relative pointer in practice |

Because the pointer really is rendered, the reliable way to hit the quick-settings
area is: home the pointer against an edge with repeated max-size moves (Android
clamps it, so starting position stops mattering), then `nudge:0,-40` to get inside
the panel, then `click`. Clicking the extreme corner dismisses the panel instead.

## AOA strings handshake — the shorter route to adb

`scripts/aoa-inject.sh --ads AdsDebug` performs the full AOAv2 string handshake
(`GET_PROTOCOL`, six `SEND_STRING`s, `START`) with `Manufacturer="Android"` and
`Model="AdsDebug"`. AOSP's `UsbDeviceManager` special-cases that Model value and
enables adb without any UI navigation, which is why this is worth trying before
any keystroke ladder. Observed on this unit:

- protocol answered `2`, strings plus `START` accepted
- the unit re-enumerated immediately as VID `0x18d1` PID `0x2d00` — Google's
  accessory PID, so accessory mode did take effect
- the accessory configuration is accessory-only, and macOS left it unclaimed: `ioreg`
  still listed the device while pyusb stopped seeing it, and `adb devices` stayed empty

So this route needs a **replug after the handshake** for the host to claim the new
configuration — which is also when an adb interface would be added. `Ace` is the
other special Model value (`adb` with the RSA confirmation dialog) and is worth a
second attempt for the same reason. `scripts/miko-detect.sh` now recognises both
VIDs and the accessory PID range, so it tells you which of these states you are in.

## Reaching Developer options without the cog

Because the cog is missing, the in-UI route has to be reached by focusing whatever
the panel does expose: `meta+n`, then `pgdn`/`down` to scroll, `tab` to move focus,
`enter` to activate — fired as **one burst**, since the input window is only about a
second wide (measured bursts: 891 ms and 1917 ms end to end):

```bash
scripts/aoa-inject.sh --sequence meta+n wait:300 pgdn wait:250 tab wait:200 enter
```

After flipping USB debugging, **unplug and replug the micro USB**: the accessory
configuration is what is enumerated while AOA is driving, and the adb interface
only shows up on the next enumeration. Verify with `scripts/miko-detect.sh`.

## AOA HID injection is the input path (no OTG adapter, no adb)

Confirmed on our unit: `scripts/aoa-inject.sh --chord meta+n --interval-ms 50`
polling across a cold boot successfully **pulled the notification shade down** via
AOAv2 HID over the control endpoint, with the Mac as USB host and NO OTG adapter
and NO adb. This replaces the missing keyboard/OTG hardware entirely.
Note: run the injector so it is already polling BEFORE powering the unit on, to
catch the brief boot-time input window. Stop the loop once the shade is down so it
does not keep toggling.
