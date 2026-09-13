#!/system/bin/sh
# Miko 3 root-ADB boot hook — SD-card-free route.
#
# Reached without any app install: /sdcard/klug/APPS/apps.json is repointed at
# com.miko.update_app, which the launcher opens with NO extras. UpdateActivity then
# defaults LAUNCHED_BY to FTUE, which sets launchedByLauncher=false, and the update
# engine takes the branch that unzips /sdcard/klug/downloads/APPS.zip and runs
# 3_files.l's commands[] through AppUtils.runCommands1() -> su.  ROOT.
#
# Because apps.json now points at the updater instead of the kiosk, this payload also
# starts the kiosk itself, so the display is unchanged.
HOOKDIR=/sdcard/klug/miko3
KIOSK=com.miko.mikoplus/com.miko.mikoplus.activity.appui.MikoActivity
NR=/data/local/tmp/neuterd
W=/data/local/tmp/miko3-usb-watch.sh
LOG=/data/local/tmp/miko3-hook.log

echo "[hook up=$(cut -d' ' -f1 /proc/uptime) uid=$(id -u)]" >> "$LOG"

# 1) neuterd from persistent emulated storage
cp "$HOOKDIR/neuterd" "$NR" 2>>"$LOG" || { echo "  !! neuterd copy failed" >> "$LOG"; }
chmod 755 "$NR" 2>/dev/null
pkill -f /data/local/tmp/neuterd 2>/dev/null
setsid "$NR" </dev/null >>"$LOG" 2>&1 &

# 2) NEUTER BEFORE ADB — the watchdog reboots the unit the moment it sees adbd
i=0
while [ "$i" -lt 20 ]; do
  [ "$(wc -c < /system/bin/reboot)" -lt 100 ] && break
  sleep 1; i=$((i+1))
done
if [ "$(wc -c < /system/bin/reboot)" -lt 100 ]; then
  echo "  reboot NEUTERED" >> "$LOG"
else
  echo "  !! neuter NOT applied -- not starting adbd" >> "$LOG"
  exit 1
fi

# 3) adb on TCP 5555 + USB, with an UNCONDITIONAL restart
setprop service.adb.tcp.port 5555
setprop sys.usb.config mtp,adb
setprop ctl.restart adbd
echo "  adb requested; init.svc.adbd=$(getprop init.svc.adbd)" >> "$LOG"

# 4) watcher for the life of the boot
cat > "$W" <<'WEOF'
#!/system/bin/sh
while true; do
  case "$(getprop sys.usb.config)" in *adb*) : ;; *) setprop sys.usb.config mtp,adb ;; esac
  [ "$(getprop init.svc.adbd)" = "running" ] || setprop ctl.restart adbd
  sleep 3
done
WEOF
chmod 755 "$W"
pkill -f /data/local/tmp/miko3-usb-watch.sh 2>/dev/null
setsid "$W" </dev/null >>"$LOG" 2>&1 &

settings put global stay_on_while_plugged_in 3 2>>"$LOG"

# 5) the kiosk is ours to start now -- apps.json no longer does it
am start -n "$KIOSK" >>"$LOG" 2>&1
echo "  kiosk started" >> "$LOG"

# 6) RE-ARM: rewrite APPS.zip so the next boot runs this again
mkdir -p /sdcard/klug/downloads 2>/dev/null
cp "$HOOKDIR/APPS.zip" /sdcard/klug/downloads/APPS.zip 2>>"$LOG"
echo "  re-armed" >> "$LOG"
