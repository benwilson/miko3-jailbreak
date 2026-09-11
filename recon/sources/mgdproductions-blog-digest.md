# Source digest — mgdproductions "Video Calling Vulnerabilities in Miko Smart Kid Robots"

- URL: <https://blog.mgdproductions.com/miko-robots-vulnerabilities/>
- Author: Marcel (MarcelD505 / MGD Productions), 28 Mar 2026.
- Full verbatim text archived alongside this file: `mgdproductions-blog-fulltext.txt`.
- Retrieval: the blog host is blocked on the local network by a UniFi firewall
  doing TLS interception (that is the "cert verify failed" / "Web Page Blocked").
  Retrieved off-network via a reader proxy.

> Disclosure banner (verbatim): "All vulnerabilities detailed in this post were
> responsibly disclosed to Miko in June 2025 and have since been fully patched.
> Miko deployed fixes three weeks after the initial report, all robots are
> required to update to a patched version before they can be used online."

## Physical access — the part that matters for us

- **The external/outside USB port is charge-only.** Verbatim: "the outside usb
  port does not do data at all. It only does charging."
- **A separate, hidden internal micro USB port carries data.** Verbatim: after
  "removing a few bottom screws to lift the top off... there is a hidden micro
  usb port that actually provided data!"
- Initial foothold for enabling dev options was a UI exploit, not USB: from a
  webview privacy-policy link a kid could reach Android Bluetooth settings, then
  "the stock android settings app and enable developer options and OEM unlocking."

## Chip / boot / OS

- **SoC: MediaTek** (exact model not published). Verbatim: "These robots also
  have a vulnerable mediatek chip that should be able to be unlocked with tools
  like mtkclient. But for some unknown reason, that didn't work and it gave me a
  strange error." → so **NOT Rockchip**; MTK BROM path is a candidate but errored
  for the researcher.
- **Bootloader:** OEM-unlock reachable, but "Unlocking the bootloader through
  fastboot requires confirmation from the volume up button. But the volume up
  button on the head of the robot didn't trigger this." No exact fastboot command
  or key combo published.
- **Android build: userdebug.** "it turned out to be root by default! The robot
  runs on a userdebug rom." ADB shell = root, over the hidden internal micro-USB.
  No ADB-over-TCP port or enable command given. No Android version number given.
- **Not covered anywhere:** exact SoC model, RAM, storage size, UART/serial or
  baud, partition layout, filesystem, mount points.

## Hardware-revision note (bears directly on our unit)

- Verbatim: "Miko claims that i had an older robot hardware revision which has a
  userdebug rom with ADB root access available. I have not been able to verify
  this statement myself as i only have one robot." → newer revisions presumed
  hardened (likely a `user` build without default ADB root); unverified.
- Kaspersky reportedly found similar issues in 2024 without naming Miko.

## Backend / cloud (context; patched — mostly out of scope for local rooting)

- Robot ID format `M3E045543`: `M3E` = Miko 3 model, `045543` = numeric unit id.
- Linking code doubles as password, derived from the id:
  `password = String.format("%06X", (Integer.parseInt(id_suffix,16) + 852) * 2)`.
- Central domain redacted; a subdomain hosted a "simulator dashboard" (AI test
  tool). Storage via leaked Linode keys → Google storage bucket (OTA download,
  read-only).
- Endpoints seen: `/v1/checkAuthentication/{id}`, `/login_user`,
  `/v2/getChildProfile/{id}`, `/v2/updateChildProfile/{id}`,
  `/v1/getappConfiguration1/{id}`, `/api/v2/push-notification`,
  `/api/v2/agora/token`, `/v1/full_data_reset/{id}`.
- **OTA manifest format is interesting for persistence research:** JSON bundling
  APKs plus an arbitrary `commands` array of shell commands (mv/rm examples in the
  fulltext). Downloadable via the leaked keys; no documented write access.
- Video calling via agora.io; channel name = robot id. (All backend issues were
  patched ~3 weeks after report.)

## Reddit thread (r/hacking 1r7oxdm) — NOT yet retrieved

Blocked by Reddit anti-bot on every off-network route the cloud agent tried
(www/old/json 403 or login gate, jina 403, all redlib mirrors challenged,
pullpush 0 results, no Wayback capture). Options: connect the Claude Chrome
extension to read it in the logged-in browser, or paste the thread text.
