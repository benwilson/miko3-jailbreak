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
