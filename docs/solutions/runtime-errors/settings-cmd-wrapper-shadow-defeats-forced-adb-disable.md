---
title: "AOSP `settings` is a thin `cmd`-wrapper script — shadow it with a positional-argument filter to block one write, not the whole command"
date: 2026-09-14
category: runtime-errors
module: "Miko 3 root-adb recovery automation (bootagent/native/neuterd.c, scripts/autonomous-recovery.py)"
problem_type: runtime_error
component: tooling
symptoms:
  - "adb drops within ~25-30s of a normal kiosk boot even though /system/bin/reboot is already shadowed and confirmed neutered (24-byte no-op, not the real ELF)"
  - "settings get global adb_enabled returns 0 shortly after the hostile service's watchdog-arming code runs, despite a background loop continuously re-asserting settings put global adb_enabled 1 every 0.5-2s"
  - "A guard meant to self-reboot only when that setting is actually forced to 0 instead fires almost immediately (under 15s), far earlier than the real ~25-30s window"
root_cause: logic_error
resolution_type: code_fix
severity: high
related_components:
  - "infrastructure"
  - "development_workflow"
tags:
  - "settings-provider"
  - "disableadb"
  - "shell-wrapper-script"
  - "mount-namespace-shadow"
  - "watchdog-neutralization"
  - "adb-persistence"
  - "android-cmd"
applies_when:
  - "A process outside your control unconditionally overwrites a Settings.Global/Secure value (or otherwise runs a specific shell command) and you need to stop just that one call, not the whole feature"
  - "You're tempted to fight the effect by re-asserting the value in a poll loop after it's already been changed, rather than intercepting the write itself"
  - "The offending call is a plain shell command (settings, pm, appops, cmd, ...) that could resolve through PATH to a script wrapper rather than a monolithic binary"
---

# AOSP `settings` is a thin `cmd`-wrapper script — shadow it with a positional-argument filter to block one write, not the whole command

## Problem

Neutering `/system/bin/reboot` (see
`docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`)
stopped the hostile watchdog service from rebooting the device, but adb kept
dropping anyway, every boot, on a fixed schedule of roughly 25-30 seconds —
long after the reboot shadow was confirmed active. The same service also
unconditionally runs `settings put global adb_enabled 0` earlier in its
init chain, independent of the reboot check, and a background loop that
just re-ran `settings put global adb_enabled 1` on a timer could not
reliably win that race.

## Symptoms

- `wc -c < /system/bin/reboot` reports 24 bytes (the shim, not the ~68KB
  real ELF) and adb still drops a predictable ~25-30s into boot.
- `settings get global adb_enabled` reads back `0` shortly after the
  hostile service's `MyService` starts, even with a loop re-asserting
  `settings put global adb_enabled 1` every 0.5-2 seconds the whole time.
- A safety-net guard designed to self-reboot only once that setting is
  confirmed forced to `0` instead fires within the first ~15 seconds of
  boot — far earlier than the service's actual init timing — because its
  very first check ran before `system_server`'s Settings service was even
  reachable, and it treated the resulting empty/error read the same as an
  explicit `0`.

## What Didn't Work

- **Re-asserting the setting in a poll loop after the fact.** Tried at both
  a 0.5s and a 2s interval (`settings put global adb_enabled 1` in a
  `for`/`sleep` loop). adb still dropped every time. The most likely
  explanation: toggling `adb_enabled` triggers a real USB gadget
  re-enumeration in the framework, and a re-assertion interval shorter than
  that re-enumeration takes just interrupts it before it can stabilize —
  racing the *effect* of an unconditional write is unreliable when you
  don't control when the write happens or how long recovery takes.
- **Treating "not equal to 1" as proof the value was forced to 0.** The
  self-reboot guard's first version used `[ "$cur" != "1" ]`. An empty or
  error response from `settings get` (which happens for a few seconds right
  after boot, before `system_server`'s Settings service answers) satisfies
  that condition just as well as a real `0` does, causing a false-positive
  self-reboot with no connection to the actual watchdog behavior at all.

## Solution

Check whether the offending command is a compiled binary or just a script
wrapper before assuming it can only be blocked wholesale.

```
$ adb shell 'head -c4 /system/bin/settings | xxd; wc -c < /system/bin/settings; cat /system/bin/settings'
00000000: 2321 2f73                                #!/s
35
#!/system/bin/sh
cmd settings "$@"
```

`/system/bin/settings` on this build is a 35-byte shell script that
delegates everything to the separate `cmd` binary — not a monolithic
executable. That makes it exactly as shadowable as `/system/bin/reboot`
already was (same bind-mount-in-init's-global-mount-namespace mechanism,
see `bootagent/native/neuterd.c:17-21`), but here the shadow should
**filter**, not block outright, since other `settings` calls still need to
work normally:

```c
// bootagent/native/neuterd.c:89-95 — the replacement script content
static const char SFILT[] =
    "#!/system/bin/sh\n"
    "if [ \"$1\" = \"put\" ] && [ \"$2\" = \"global\" ] && [ \"$3\" = \"adb_enabled\" ] && [ \"$4\" = \"0\" ]; then\n"
    "exit 0\n"
    "fi\n"
    "exec cmd settings \"$@\"\n";
```

Because both the stock wrapper and the replacement are shell scripts, the
ELF-vs-script magic-byte check used for `reboot` doesn't distinguish them.
`settings_needs_patch()` instead looks for a content marker
(`bootagent/native/neuterd.c:133-141`, checks for the substring
`adb_enabled` in the first 128 bytes) to decide whether the shadow still
needs (re-)applying — the same self-healing 5-second loop that maintains
the `reboot` shadow now maintains this one too
(`bootagent/native/neuterd.c:166-171`).

The self-reboot guard's false positive was fixed by matching the literal
value the hostile write actually produces, plus a startup grace period
(`scripts/autonomous-recovery.py:186-189`):

```diff
  setsid sh -c '
+   sleep 3
    for i in $(seq 1 300); do
      cur=$(settings get global adb_enabled 2>/dev/null)
-     if [ "$cur" != "1" ]; then
+     if [ "$cur" = "0" ]; then
        ...
```

Landed in commit `feat(bootagent): shadow /system/bin/settings to defeat
disableADB() at the source` (888833b). Verified live: adb stayed reachable
and the watched service ran normally (no crash, same pid throughout) past
150+ seconds of uptime on a normal boot, versus dropping within 25-30
seconds on every prior attempt.

## Why This Works

An unconditional write from a process you don't control is a race you can
only lose reliably if you're reacting to its *effect* — you don't control
when it fires, and undoing it may itself have side effects (a settings
change can trigger a real subsystem re-configuration, like a USB gadget
re-enumeration, that takes measurable time and can be re-interrupted by an
over-eager retry). Shadowing the *mechanism* the write goes through removes
the race entirely: the write never happens, so there's nothing to detect or
undo. This generalizes past `settings`: any AOSP `cmd`-backed shell command
(`pm`, `appops`, `dumpsys`, `svc`, ...) may be a thin wrapper script rather
than a real binary, and the same technique — read the first few bytes to
check for `#!`, then bind-mount a positional-argument filter over it in the
mount namespace the target process actually shares — applies to any of
them.

## Prevention

- **Before writing a loop that re-asserts a value against a hostile writer,
  check whether the writer's command resolves to a script wrapper.**
  `head -c4 <path> | xxd` distinguishes a shell script (`23 21` / `#!`) from
  an ELF binary (`7f 45 4c 46`) in one call. A script wrapper can be
  shadowed with a *filter*, which is strictly more reliable than racing the
  effect of a write you can't prevent.
- **A shadow that must distinguish "still stock" from "already patched"
  can't rely on the ELF-vs-script trick when both versions are scripts.**
  Use a content marker unique to your replacement (a string it contains
  that the original never would) instead, checked against a small read of
  the file's head.
- **When a guard reacts to "the value is no longer what I expect," match
  the literal value the hostile actor writes, not "anything other than the
  value I want.**" Early in boot, a query against a not-yet-ready system
  service can return empty or error, which a negative check
  (`!= expected`) will misclassify as "changed" even though nothing wrote
  anything.

## Related Issues

- `docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md` —
  the sibling finding from the same investigation (don't suppress the
  process, disarm the specific action) and the origin of the
  `/system/bin/reboot` shadow this technique extends. That doc's own
  "Solution" section still describes re-asserting `adb_enabled` in a loop as
  the fix for the settings side — this doc supersedes that specific claim.
- `docs/boot-sequence.md` — full incident writeup and live timing evidence
  for both the reboot-watchdog and disableADB() mechanisms
- `docs/persistent-adb-normal-boot.md` — why the mount-namespace shadow has
  to happen in init's global namespace, not an app's, for a zygote-forked
  service to see it
