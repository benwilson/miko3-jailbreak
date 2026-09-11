# Firmware Reference — Miko 3

Firmware images are **not committed** to the repository — they are large,
device-specific, and contain copyrighted code. This file documents what was
dumped, from where, and how to reproduce the dump or restore from it.

Images are stored locally in `firmware/dump/` (gitignored).

## Device Context

- **Device:** Miko 3 (Chidakashi Technologies)
- **SoC:** MediaTek MT8168 (quad Cortex-A35)
- **Android:** 9 (SDK 28), `userdebug` build
- **Build fingerprint:** `MIKO3/full_tb8168p1_64_bsp/tb8168p1_64_bsp:9/PPR1.180610.011/zhangwei06102241:userdebug/test-keys`
- **Build date:** 2023-06-10
- **Serial:** `MIKO3250XXM3Q0636CB`
- **eMMC:** 32 GB (60,620,800 sectors × 512 bytes)
- **GPT GUID:** `957CCF2A-CC98-4938-8911-B3D792553A42`
- **Dump method:** Root ADB shell in factory mode (`scripts/factory-root.sh`)

## Complete Partition Manifest

| # | Dump File | Source Partition | Block Device | Size | SHA-256 |
|---|---|---|---|---|---|
| — | `preloader.img` | Preloader (primary) | `mmcblk0boot0` | 4.0 MB | `3cacb7b8dbc536965a3eb3eb9c199e55b03f192212cf8b5a2d651c3b1cf9a000` |
| — | `preloader2.img` | Preloader (backup) | `mmcblk0boot1` | 4.0 MB | `bb9f8df61474d25e71fa00722318cd387396ca1736605e1248821cc0de3d3af8` |
| — | `gpt_header.img` | GPT header | `mmcblk0` (first 34 sectors) | 17 KB | `9bd14de76b0376694247dc27a28c0346e62201326b4f25849d9110c3267f3d83` |
| 1 | `proinfo.img` | proinfo | `mmcblk0p1` | 3.0 MB | `692fba3fc2f88decc6ce367878dd2bdd4c13409f69c97b6ab52d1e6460a2450c` |
| 6 | `nvram.img` | nvram | `mmcblk0p6` | 5.0 MB | `fda64993f5c5067003d9dae279b482a3231c4264790127e023083420898f540a` |
| 9 | `persist.img` | persist | `mmcblk0p9` | 48 MB | `152ba99dbaf6c7dde5955a8484835194ed4fc0f20a0ea774667f148a25cb03c4` |
| 10 | `nvcfg.img` | nvcfg | `mmcblk0p10` | 8.0 MB | `d1ba0203db3b0d5b44ba68caf4a96bf8f226a35573ae19032daa6d306e3a05cc` |
| 11 | `seccfg.img` | seccfg | `mmcblk0p11` | 256 KB | `6315cb87ed108c450833a4be30e0a6b1c16940b12c6b470281823c3248876578` |
| 12 | `lk.img` | lk (Little Kernel) | `mmcblk0p12` | 1.0 MB | `08f02a425aead4664ad5e69553e590297fa121b148eac9a7de8926c553f871cf` |
| 13 | `lk2.img` | lk2 (LK backup) | `mmcblk0p13` | 1.0 MB | `08f02a425aead4664ad5e69553e590297fa121b148eac9a7de8926c553f871cf` |
| 14 | `boot.img` | boot | `mmcblk0p14` | 16 MB | `de36ec6c22419da8ff1c7af3068c6265aa0f945e0539d4b8fd7d9273114483d0` |
| 15 | `recovery.img` | recovery | `mmcblk0p15` | 16 MB | `f9dd5c6375f5000be9efad0438cffd536abd12a3e14d5ad0506c89cb066526c2` |
| 16 | `para.img` | para | `mmcblk0p16` | 512 KB | `fa25dd82dddd4eccf4b69ae94ad350f148a682e4e7091d9153713ae3413960f9` |
| 17 | `logo.img` | logo | `mmcblk0p17` | 8.0 MB | `6a8969e40606e4851b88a001e45490de9118147be593e4b99538f75311db8f72` |
| 18 | `dtbo.img` | dtbo | `mmcblk0p18` | 8.0 MB | `4c2f8cdfcd1296efc52b19fb8c62ba11f150b343b1fb3e0d7f1386f901210069` |
| 20 | `frp.img` | frp | `mmcblk0p20` | 1.0 MB | `eccfa95f82fcbfbf9c15a9f2f50749a33b6fd5d66604c0768ec8daa1612e7a36` |
| 21 | `nvdata.img` | nvdata | `mmcblk0p21` | 32 MB | `b9aac8dde087bff20a22c81463f685e48144854584b8f6242d41204b1749a8b5` |
| 22 | `tee1.img` | tee1 (TrustZone) | `mmcblk0p22` | 5.0 MB | `48be5834db47a0f20aed32832d86179f67f9dc27e91d3f9e46ae15e7865ee724` |
| 23 | `tee2.img` | tee2 (TZ backup) | `mmcblk0p23` | 5.0 MB | `48be5834db47a0f20aed32832d86179f67f9dc27e91d3f9e46ae15e7865ee724` |
| 27 | `vbmeta.img` | vbmeta | `mmcblk0p27` | 11 MB | `b4cb658d08c245446b12d7d807e6ade6692ddd4cf14577930babeaed381d6218` |
| 28 | `system.img` | system | `mmcblk0p28` | 8.3 GB | `2c65101daf11cff05ca147069033fa653c48e9a1af98b3dc730503e8ed4852c6` |
| 29 | `vendor.img` | vendor | `mmcblk0p29` | 400 MB | `729bcad1dc124dce9c43cb32e925f810040d04ba2bd4ca1bbd95e41f9fe8b2f1` |

**Total:** 22 images, 8.8 GB.

Partitions not dumped (empty, uninteresting, or too large):
- `boot_para` (p2), `cam_vpu1-3` (p3-5), `protect1-2` (p7-8), `expdb` (p19),
  `kb` (p24), `dkb` (p25), `metadata` (p26), `cache` (p30), `userdata` (p31)

## Notable Observations

- **`lk.img` = `lk2.img`** — The Little Kernel bootloader and its backup are byte-identical.
- **`tee1.img` = `tee2.img`** — TrustZone images are byte-identical.
- **`preloader.img` ≠ `preloader2.img`** — The two preloader copies differ.
  `boot0` is the primary; `boot1` is the fallback. They likely contain different
  per-device calibration data fused at the factory.
- **`seccfg.img`** (256 KB) — This is the security configuration partition.
  It controls bootloader lock state. Modifying it incorrectly can brick the device.
  **Do not write to this partition without a verified backup.**

## How to Dump Your Own Firmware

### Prerequisites
1. Get root ADB on the Miko 3: `scripts/factory-root.sh`
2. Make sure ADB is working: `adb shell id` should show `uid=0(root)`

### Method 1: Automatic (recommended)
```bash
scripts/factory-root.sh --dump
```
This dumps all critical partitions to `firmware/dump/` automatically.

### Method 2: Manual
```bash
# Dump a single partition by name
adb shell "dd if=/dev/block/mmcblk0p14 bs=4096" > firmware/dump/boot.img

# Dump the preloader (hidden eMMC boot region)
adb shell "dd if=/dev/block/mmcblk0boot0 bs=4096" > firmware/dump/preloader.img
adb shell "dd if=/dev/block/mmcblk0boot1 bs=4096" > firmware/dump/preloader2.img

# Dump the GPT header
adb shell "dd if=/dev/block/mmcblk0 bs=512 count=34" > firmware/dump/gpt_header.img

# List all partitions by name
adb shell "ls -la /dev/block/platform/*/by-name/"
```

### Verify your dumps
```bash
# Compute SHA-256 for all images
for f in firmware/dump/*.img; do
  shasum -a 256 "$f"
done

# Compare a dump against the device
adb shell "dd if=/dev/block/mmcblk0p15 bs=4096" | md5sum
md5sum firmware/dump/recovery.img
# Hashes should match.
```

## How to Restore Firmware

### From root ADB (factory mode)
```bash
scripts/restore-firmware.sh boot     # restore a single partition
scripts/restore-firmware.sh --check  # verify all dumps against device
```

### From fastboot
```bash
# Enter fastboot via META
scripts/meta-try.sh FASTBOOT

# Flash individual partitions
fastboot flash boot firmware/dump/boot.img
fastboot flash recovery firmware/dump/recovery.img
fastboot flash lk firmware/dump/lk.img
fastboot flash vbmeta firmware/dump/vbmeta.img
```

### From mtkclient (BROM mode — Linux recommended)
```bash
python mtkclient.py write boot firmware/dump/boot.img
python mtkclient.py write recovery firmware/dump/recovery.img
```

## ⚠️ Safety Warnings

1. **NEVER write to `mmcblk0boot0` or `mmcblk0boot1` (preloader).**
   Corrupting the preloader is a **hard brick** — the device will not power on
   and recovery requires a BROM test point or JTAG. These partitions should only
   be written via mtkclient in BROM mode with a verified backup.

2. **NEVER write to `seccfg` (mmcblk0p11) without a verified backup.**
   This partition controls the bootloader lock state. An invalid value can
   prevent the device from booting.

3. **Always verify your backup before flashing.** Run `scripts/restore-firmware.sh --check`
   or compare hashes manually.

4. **The partition layout is device-specific.** These offsets and names apply to
   the Miko 3 with the MT8168 SoC and the GPT above. Other revisions or models
   may differ.

5. **dm-verity protects system and vendor.** If you modify these partitions,
   you must also update vbmeta or disable dm-verity, or the device will refuse
   to boot.
