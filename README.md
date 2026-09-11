# miko3-jailbreak

Research notes and tooling for gaining root / unrestricted access on a personally
owned **Miko 3** robot (Chidakashi Technologies). The Miko 3 is an Android-based
device, so most of the work here is standard Android bring-up: finding a serial
or USB debug path, dumping firmware, unpacking partitions, and getting a shell
that survives reboot.

## Scope

- Hardware is owned outright by the repo author. Everything here is local device
  modification — no attacks against Miko's servers, other people's units, or
  accounts.
- Warranty is forfeit the moment anything in `recon/` is acted on. Assume any
  step can brick the unit.

## Layout

| Path        | Contents |
|-------------|----------|
| `docs/`     | Writeups: teardown, boot chain, findings, how-to-reproduce |
| `recon/`    | Enumeration results — ports, services, `getprop` dumps, partition maps |
| `firmware/` | Firmware images and extracted partitions (gitignored; see `firmware/README.md`) |
| `tools/`    | Third-party tooling, vendored or as submodules (unpackers, flashers) |
| `scripts/`  | Repo-local helper scripts (dump, extract, push, log capture) |
| `notes/`    | Working scratch — raw session logs, dead ends, ideas |

Large binaries are deliberately kept out of git. Record where an image came from
and its `sha256` in `firmware/README.md` instead of committing the image.

## Status

Nothing attempted yet. Next step is passive recon: identify the SoC, check for
an exposed UART on the mainboard, and see whether ADB is reachable over USB or
the network as shipped.
