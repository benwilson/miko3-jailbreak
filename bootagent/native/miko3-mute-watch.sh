#!/system/bin/sh
# miko3-mute-watch.sh: relays the physical mute button to the launcher (owner 2026-10-02).
#
# The button is its own input device, "mutekey" (/dev/input/event2). It reports a
# switch (EV_SW, SW_MID_MUTE_KEY = 0x17), not a key, and has no key layout, so Android
# drops it and no app sees it. Reading the device needs root, so the boot agent's root
# payload materializes this script and runs it detached. Each press becomes one
# explicit broadcast to the launcher's non-exported MuteKeyReceiver, which toggles
# STREAM_MUSIC's mute; only root or the system can reach that receiver.
#
# Press rule (the capture could not tell a momentary button from a latching one):
#   value 1 is always a press;
#   value 0 is a press too, unless a 1 came less than 1 s before it (that 0 is the
#   release of a momentary press). So a latching switch toggles on each change.
#
# Robustness: a device that closes is reopened after 5 s, an absent or unreadable one
# retried every 60 s, so the loop never spins; a failed broadcast is logged and reading goes on.
# Every event is logged raw at debug (logcat tag Miko3MuteKey).
#
# Each event is read with its own `dd` from one held-open descriptor (the function's
# stdin), so no event is lost between reads and a read returns exactly one event.
#
# Host test hooks (scripts/tests/test_mute_key.py): MUTE_DEV, MUTE_EVSZ, and
# MUTE_WATCH_TEST=1, which logs to stdout, prints the broadcast instead of sending
# it, and exits when the "device" runs out.

DEV="${MUTE_DEV:-/dev/input/event2}"
TAG=Miko3MuteKey
EV_SW=5
SW_MID_MUTE_KEY=23
PRESS_WINDOW_MS=1000
TEST="${MUTE_WATCH_TEST:-}"
FALLBACK_LOG=/data/local/tmp/miko3-boot.log

# struct input_event is 24 bytes to a 64-bit reader and 16 to a 32-bit one; dd is the
# reader here, and it has the system's primary ABI (arm64-v8a on the robot).
EVSZ="${MUTE_EVSZ:-}"
if [ -z "$EVSZ" ]; then
  case "$(getprop ro.product.cpu.abi 2>/dev/null)" in
    *64*) EVSZ=24 ;;
    *) EVSZ=16 ;;
  esac
fi

AM_BROADCAST="am broadcast --user 0 -f 0x10000000 -n com.miko3.launcher/.MuteKeyReceiver -a com.miko3.launcher.action.MUTE_KEY"

# say <d|i|w> <message>
say() {
  if [ -n "$TEST" ]; then
    echo "$1 $2"
  elif command -v log >/dev/null 2>&1; then
    log -p "$1" -t "$TAG" "$2"
  else
    echo "[mute $1] $2" >> "$FALLBACK_LOG"
  fi
}

relay() {
  if [ -n "$TEST" ]; then
    echo "RELAY $AM_BROADCAST --es why $1"
    return 0
  fi
  $AM_BROADCAST --es why "$1" >/dev/null 2>&1 || say w "mute broadcast failed (launcher not installed?)"
}

# Reads events from stdin until a read comes back short. Returns 0 if any event was read.
read_events() {
  got=1
  last_s=""
  last_u=0
  while true; do
    # shellcheck disable=SC2046
    set -- $(dd bs="$EVSZ" count=1 2>/dev/null | od -An -v -tx1)
    [ "$#" -eq "$EVSZ" ] || return "$got"
    got=0
    sec=$((0x$4$3$2$1))
    if [ "$EVSZ" -eq 24 ]; then
      usec=$((0x${12}${11}${10}$9))
      shift 16
    else
      usec=$((0x$8$7$6$5))
      shift 8
    fi
    type=$((0x$2$1))
    code=$((0x$4$3))
    value=$((0x$8$7$6$5))
    say d "raw type=$type code=$code value=$value t=$sec.$(printf '%06d' "$usec")"
    [ "$type" -eq "$EV_SW" ] && [ "$code" -eq "$SW_MID_MUTE_KEY" ] || continue
    if [ "$value" -eq 1 ]; then
      last_s=$sec
      last_u=$usec
      say i "mute pressed (switch on)"
      relay on
    elif [ "$value" -eq 0 ]; then
      # Seconds and microseconds apart, so the sum stays inside mksh's 32-bit arithmetic.
      if [ -n "$last_s" ] && [ $(( (sec - last_s) * 1000 + (usec - last_u) / 1000 )) -lt "$PRESS_WINDOW_MS" ]; then
        say d "release of a momentary press, not a toggle"
      else
        say i "mute pressed (switch off, latching)"
        relay off
      fi
      last_s=""
    fi
  done
}

say i "mute watcher up: $DEV, ${EVSZ}-byte events"
while true; do
  if [ -r "$DEV" ]; then
    if read_events < "$DEV"; then
      say w "mute device closed; reopening in 5 s"
      [ -n "$TEST" ] && exit 0
      sleep 5
    else
      say w "mute device read failed (wrong event size?); retrying in 60 s"
      [ -n "$TEST" ] && exit 0
      sleep 60
    fi
  else
    say w "mute device $DEV absent; retrying in 60 s"
    [ -n "$TEST" ] && exit 0
    sleep 60
  fi
done
