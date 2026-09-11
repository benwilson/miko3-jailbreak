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
   Settings.
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
