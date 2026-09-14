---
title: "Extract files from ext2/3/4 firmware images on macOS with debugfs, not mount"
module: "Firmware partition image extraction on macOS (firmware/dump*/*.img)"
date: "2026-09-14"
problem_type: tooling_decision
category: tooling-decisions
component: tooling
severity: high
related_components:
  - "infrastructure"
  - "development_workflow"
tags:
  - "debugfs"
  - "e2fsprogs"
  - "ext4"
  - "firmware-image-extraction"
  - "macos"
  - "homebrew-keg-only"
  - "apk-extraction"
applies_when:
  - "Need to pull specific files (APKs, init.rc, keylayout, packages.xml, vendor binaries) out of an ext2/3/4 firmware partition image (system.img, vendor.img, userdata.img) on macOS"
  - "macOS has no native ext4 driver, so mount and hdiutil attach cannot open the image"
  - "Only a handful of files are needed, not a full mount or full-image copy of a multi-gigabyte dump"
  - "A file's on-device path is under /storage/emulated/0/... (a bind-mount of /data/media/0, so it lives on the userdata dump, not a separate sdcard image)"
symptoms:
  - "hdiutil attach and mount fail or report an unrecognized filesystem on a .img partition dump"
  - "debugfs: command not found even though brew install e2fsprogs finished without error"
  - "No obvious way to browse or extract one file from a 20GB ext4 image without mounting or copying the whole thing"
root_cause: missing_tooling
resolution_type: tooling_addition
---

# Extract files from ext2/3/4 firmware images on macOS with debugfs, not mount

## Context

This repo (`miko3-jailbreak`) stores full Android firmware partition dumps under `firmware/dump/` and `firmware/dump-parental-locked/` — `system.img` (8.3GB), `vendor.img` (400MB), and `userdata.img` (20GB) — which are plain ext2/ext4 filesystem images (`file firmware/dump/system.img` → "Linux rev 1.0 ext2 filesystem data..."; `file firmware/dump-parental-locked/userdata.img` → "Linux rev 1.0 ext4 filesystem data..."). Research work on this repo regularly needs to pull one specific file — an APK, a config, a log — out of one of these images without disturbing the rest. macOS has no native ext2/3/4 driver, so `mount` and `hdiutil attach` fail outright, and the usual Linux-image toolkit (`simg2img`, `img2simg`, `debugfs`, `unsquashfs`, `fuse-ext2`, `ext4fuse`) is not installed by default, leaving only `7z` (unreliable for this — see Why This Matters) available out of the box.

**This exact problem was independently rediscovered at least four times in three days before being written down here** (session history): once via a `pi` session on 2026-09-12 while recovering a deleted `ServiceExam` APK, once via a short, focused Claude Code session on 2026-09-13 pulling `APPS.zip` out of `userdata.img`, once via a longer Claude Code session reusing the technique across 2026-09-13→14, and again in the session that produced this doc — each time re-deriving the same `debugfs` + brew-prefix-path answer from scratch. That repeated rediscovery is the signal this was worth documenting.

## Guidance

Install `e2fsprogs` via Homebrew and use its `debugfs` tool in read-only, single-command mode (`-R`) to browse and extract individual files directly from the image — no mount, no full-image copy or conversion:

```bash
brew install e2fsprogs
```

Homebrew installs `e2fsprogs` **keg-only** (it conflicts with tooling macOS expects, so Homebrew deliberately does not link it onto `PATH` and will not `brew link --force` it either). Always resolve the binary through the brew prefix rather than hardcoding a path:

```bash
DEBUGFS=$(brew --prefix e2fsprogs)/sbin/debugfs

# Browse a directory inside the image (read-only, no mount):
$DEBUGFS -R "ls -l /app" firmware/dump-parental-locked/userdata.img

# Dump one specific file out of the image to a real file on disk:
$DEBUGFS -R "dump /app/com.example.root.serviceexam-i7wVMoIJW0lO8uJ_B0RCDA==/base.apk tools/serviceexam.apk" firmware/dump-parental-locked/userdata.img
```

Prefer `$(brew --prefix e2fsprogs)/sbin/debugfs` (the brew-prefix symlink) over a hardcoded Cellar path like `/opt/homebrew/Cellar/e2fsprogs/1.47.4/sbin/debugfs` (session history: one session used the versioned Cellar path successfully, then switched back to the prefix path later in the same session) — the prefix symlink survives `brew upgrade`, the versioned path does not.

Two path-mapping notes worth knowing before you go looking for a file:

- `userdata.img`'s root corresponds to `/data` on the live device, so a path like `/app/<pkg>-<hash>/base.apk` inside the image is `/data/app/...` on-device — cross-reference with a pulled `packages.xml`'s `codePath` values to find the right directory.
- On Android, `/storage/emulated/0/...` is a bind-mount view of `/data/media/0` (session history) — a file that appears to live under `/storage/emulated/0/...` on a running device is physically on the `userdata` partition dump, not a separate "sdcard" image. Look for it there, not on `system.img`/`vendor.img`.

A `dump_file: Operation not permitted while changing ownership...` warning during `dump` is benign (session history) — `debugfs` is just failing to `chown` the output file because the process isn't running as root; the extracted content is still complete and correct.

## Why This Matters

The keg-only gotcha is the part worth writing down: `brew install e2fsprogs` reports success, but running bare `debugfs -R ...` afterward fails with a plain "command not found," which reads exactly like the install silently failed or didn't ship the tool at all. Without knowing to check `brew --prefix e2fsprogs`, that failure invites wasted time re-installing, hunting for a `brew link --force` that Homebrew withholds on purpose, or reaching for an inferior alternative.

Session history confirms `ext4fuse` (a FUSE-based mount path) simply fails to install on this host, and `7z l <image>` can list some ext4 content but is not reliable for full extraction — in one prior session it failed to find a target file not because the tool broke, but because the file lived on a different partition image than the one being searched (a partition-selection mistake, not a `7z` bug, but easy to misread as "the tool doesn't work"). `debugfs -R` is the one approach that has actually worked, every time it's been tried, across three prior independent sessions (plus this one) and two different coding agents (Claude Code and Pi).

Once the full path is resolved, the technique itself is cheap and safe: `debugfs -R` opens the image read-only, lists or dumps files in seconds, and never requires copying or converting the multi-GB image — a meaningful savings against images in the 400MB-to-20GB range, where a full copy or `simg2img`-style conversion would be slow and disk-expensive, and would also be the wrong tool anyway since these are already raw ext2/4 images per `file`, not Android's sparse-image container that `simg2img` targets.

## When to Apply

- Working on macOS (no native ext2/3/4 mount support).
- Need to inspect or extract specific files from an ext2/3/4-formatted disk or partition image without mounting it.
- Specifically: any future pass over `firmware/dump/`, `firmware/dump-parental-locked/`, or `firmware/agent-backups/` in this repo — pulling more APKs, checking other partitions, or verifying file contents against `packages.xml` records.

## Examples

Before (bare command, fails despite successful install):
```bash
$ debugfs -R "ls /" firmware/dump/system.img
zsh: command not found: debugfs
```

After (full keg path, works):
```bash
$ $(brew --prefix e2fsprogs)/sbin/debugfs -R "ls /" firmware/dump/system.img
 2  (12) .    2  (12) ..   11  (20) lost+found   ...
```

Real extraction used in the session that produced this doc — pulling `com.example.root.serviceexam`'s 106MB `base.apk` out of the 20GB `userdata.img` in seconds:
```bash
DEBUGFS=$(brew --prefix e2fsprogs)/sbin/debugfs
$DEBUGFS -R "ls -l /app" firmware/dump-parental-locked/userdata.img
$DEBUGFS -R "dump /app/com.example.root.serviceexam-i7wVMoIJW0lO8uJ_B0RCDA==/base.apk tools/serviceexam.apk" firmware/dump-parental-locked/userdata.img
```
The resulting `tools/serviceexam.apk` was later `jadx`-decompiled and its manifest package name matched `com.example.root.serviceexam`, confirming a byte-correct extraction. The same pattern was reused to pull `game_robot_maker.apk` from `/system/app/game_robot_maker.apk` inside `system.img`.

An earlier session (session history) used the same tool to pull a 330MB `APPS.zip` from `userdata.img` (found at a specific inode via `debugfs`'s `ls -l`, then extracted with `dump`) and verified it with `unzip -l` — file size matched the inode's reported size exactly, confirming the technique generalizes past APKs to any file type.

## Related

- [`mediatek-preloader-meta-console-route.md`](../tooling-decisions/mediatek-preloader-meta-console-route.md) — a sibling macOS host-tooling workaround for this same device: reaching a locked Miko 3 through the MediaTek preloader's serial console when adb/UI access is unavailable. Different mechanism (live serial handshake vs. offline filesystem-image inspection), but both belong to the same "macOS-side tooling for a locked-down device" cluster.
- [`aidl-interface-mismatch-kiosk-splash-loop.md`](../integration-issues/aidl-interface-mismatch-kiosk-splash-loop.md) — its Solution section pushes a pre-staged `com.example.root.serviceexam` `base.apk` (`file1.ia`) onto the device via `adb`/`pm install`, without documenting how that file was originally obtained. This doc's technique is a likely source for acquiring that exact file — see the real extraction example above, which pulls `com.example.root.serviceexam`'s `base.apk` directly.
- `docs/hardware/boot-hal-reference.md` (device-research doc, not a compound-engineering learning) documents this same `debugfs` technique in the narrower context of one specific research pass over `boot.img`/`system.img`/`vendor.img`; this doc is the durable, repo-wide, reusable version of that technique.
