---
title: "toybox 0.7.6 stty can't clear a tty's input flags, and its raw keyword sets them"
date: 2026-09-29
category: tooling-decisions
module: "Miko 3 launcher ears: voice direction port setup (shared VoiceDirection)"
problem_type: best_practice
component: infrastructure
applies_when:
  - "Configuring a serial tty on the Miko 3 (toybox 0.7.6, Android 9) without a native helper"
  - "Frames from a serial peripheral arrive with bad CRCs, the top bit missing, or bytes 0x0D, 0x11 or 0x13 changed or dropped"
tags:
  - "toybox"
  - "stty"
  - "termios"
  - "uart"
  - "voice-direction"
---

# toybox 0.7.6 stty can't clear a tty's input flags, and its raw keyword sets them

## Context

The launcher reads the NC direction chip on `/dev/ttyS1` from Java, and Java cannot set a port's termios. The vendor library configured it before, but it was dropped from the chip path (see `docs/solutions/runtime-errors/vendor-dsp-direction-open-segfaults-launcher-miko3.md`). So the launcher configures the port with the system `stty`, which on this robot is toybox 0.7.6.

On the robot, on 2026-09-29, every frame arrived with a bad CRC:

- module `a3` came through as `23`, because the top bit was stripped;
- some frames were 17 or 18 bytes instead of 19, because 0x11 and 0x13 were swallowed as XON/XOFF;
- 0x0D became 0x0A.

`stty -F /dev/ttyS1 -a` showed `istrip icrnl ixon ixoff inpck` still on after every attempt to clear them:

- `stty -F dev 115200 raw ...` turns them **on**. Its `raw` keyword does the opposite of GNU's for these input flags.
- Negating them in one long argument list, or one per call, still leaves `icrnl`, `ixon`, `ixoff` and `inpck` set. `-istrip`, `-inlcr` and `-igncr` do stick.
- `stty -F dev -g` printed the input-flags word as `ffffff14`, a value with every high bit set.

## Guidance

Set speed and modes by flag, which works. Then write the saved settings back with the input-flags field zeroed, and read them back to check:

```sh
stty -F /dev/ttyS1 115200 cs8 -cstopb -parenb clocal -crtscts -hupcl -opost -isig -icanon -iexten -echo -echoe -echok -echonl
g=$(stty -F /dev/ttyS1 -g)             # e.g. ffffff14:0:18b2:0:0:0:...
stty -F /dev/ttyS1 "0:${g#*:}"         # input flags field set to 0
stty -F /dev/ttyS1 -g                   # must now start with "0:"
```

After that, `stty -a` shows `-inpck -istrip -icrnl -ixon -ixoff`, and every frame arrives intact with a valid CRC. `VoiceDirection`'s port setup does exactly this and refuses the port unless the read-back starts with `0:` (`VoiceDirection.zeroInputFlags` and `inputFlagsZero`).

## Why This Matters

The symptoms look like a protocol problem: wrong module bytes, bad CRCs, frames of the wrong length. An afternoon can go into decoding a "different" frame type that is really the same frame with bit 7 stripped. `stty -a` looking plausible for the flags you set, while `-g` shows a garbage input-flags word, is the tell.

## When to Apply

Any time the robot's shell or an app configures a tty with toybox `stty`. Never use `raw`. Verify with `-g`, not `-a` alone.

## Examples

- Before, with `raw`: `58585542 23 03 3b 01 05 2f1a7f20 55764a0349`: module `23`, CRC fails, 18 bytes.
- After the `-g` rewrite: `58585542 a3 03 44 01 05 00 de9c7afe 55f64a03c9`: module `a3`, CRC valid, 19 bytes.
