#!/usr/bin/env python3
"""
patch-persist-usb-config.py — set persist.sys.usb.config in a pulled
/data/property/persistent_properties file, verified via round-trip parsing.

See docs/persistent-adb-normal-boot.md for the full method and why this matters:
this file is a flat, unframed sequence of protobuf PersistentPropertyRecord
messages, no checksum, read by init before any app runs. Setting
persist.sys.usb.config here (to e.g. "mtp,adb") gets adb exposed on normal
boot without needing any app-triggered hook.

Usage:
    adb pull /data/property/persistent_properties /tmp/persistent_properties.bin
    python3 scripts/patch-persist-usb-config.py /tmp/persistent_properties.bin mtp,adb \
        /tmp/persistent_properties.new.bin
    adb push /tmp/persistent_properties.new.bin /data/local/tmp/persistent_properties.new
    adb shell 'cp /data/local/tmp/persistent_properties.new /data/property/persistent_properties
                chown root:root /data/property/persistent_properties
                chmod 600 /data/property/persistent_properties'
    adb reboot
"""
import sys


def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7f) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def write_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7f
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def parse(data):
    entries = []
    pos = 0
    while pos < len(data):
        tag, pos = read_varint(data, pos)
        assert tag == 0x0a, f"unexpected outer tag {tag:#x} at {pos}"
        rec_len, pos = read_varint(data, pos)
        rec_end = pos + rec_len
        rec = data[pos:rec_end]
        pos = rec_end

        rpos = 0
        name = None
        value = None
        while rpos < len(rec):
            ftag, rpos = read_varint(rec, rpos)
            flen, rpos = read_varint(rec, rpos)
            fval = rec[rpos:rpos + flen]
            rpos += flen
            if ftag == 0x0a:
                name = fval.decode('utf-8')
            elif ftag == 0x12:
                value = fval.decode('utf-8')
            else:
                raise AssertionError(f"unexpected inner tag {ftag:#x}")
        entries.append((name, value))
    return entries


def serialize(entries):
    out = bytearray()
    for name, value in entries:
        name_b = name.encode('utf-8')
        value_b = value.encode('utf-8')
        rec = bytearray()
        rec.append(0x0a)
        rec += write_varint(len(name_b))
        rec += name_b
        rec.append(0x12)
        rec += write_varint(len(value_b))
        rec += value_b
        out.append(0x0a)
        out += write_varint(len(rec))
        out += rec
    return bytes(out)


def main():
    if len(sys.argv) != 4:
        print(f"usage: {sys.argv[0]} <input.bin> <usb-config-value> <output.bin>", file=sys.stderr)
        sys.exit(1)

    in_path, new_value, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

    with open(in_path, "rb") as f:
        data = f.read()

    entries = parse(data)

    # Sanity check: our codec must reproduce the input exactly before we trust
    # it to write anything back to a boot-critical file.
    if serialize(entries) != data:
        print("REFUSING: round-trip of unmodified data did not match input byte-for-byte", file=sys.stderr)
        sys.exit(1)

    found = False
    new_entries = []
    for name, value in entries:
        if name == "persist.sys.usb.config":
            print(f"persist.sys.usb.config: {value!r} -> {new_value!r}")
            value = new_value
            found = True
        new_entries.append((name, value))

    if not found:
        new_entries.append(("persist.sys.usb.config", new_value))
        print(f"persist.sys.usb.config was absent; adding it as {new_value!r}")

    new_data = serialize(new_entries)

    # Re-parse what we just built and confirm it reads back correctly.
    reparsed = dict(parse(new_data))
    assert reparsed["persist.sys.usb.config"] == new_value
    assert len(reparsed) == len(dict(entries)) or not found

    with open(out_path, "wb") as f:
        f.write(new_data)
    print(f"wrote {len(new_data)} bytes to {out_path} (input was {len(data)} bytes)")


if __name__ == "__main__":
    main()
