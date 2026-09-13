#!/system/bin/sh
# Miko 3 boot hook payload — runs AS ROOT, launched by com.miko.launcher_app (the HOME app)
# from the Miko update engine, which executes 3_files.l's commands[] through
# AppUtils.runCommands1() -> su.  See hooks/README.md.
#
# Invoked from the SD card, so it and neuterd survive an OTA and every reboot.
HOOKDIR=/storage/sdcard1/miko3
NR=/data/local/tmp/neuterd
W=/data/local/tmp/miko3-usb-watch.sh
LOG=/data/local/tmp/miko3-hook.log

echo "[hook up=$(cut -d' ' -f1 /proc/uptime) uid=$(id -u)]" >> "$LOG"

# 1) materialize neuterd from the card
cp "$HOOKDIR/neuterd" "$NR" 2>>"$LOG" || { echo "  !! neuterd copy failed" >> "$LOG"; exit 1; }
chmod 755 "$NR"
pkill -f /data/local/tmp/neuterd 2>/dev/null
setsid "$NR" </dev/null >>"$LOG" 2>&1 &

# 2) wait for the reboot shadow BEFORE touching adb (the watchdog reboots on sight of adbd)
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

# 3) adb up: TCP 5555 + USB combo, then an UNCONDITIONAL restart (a running adbd
#    never picks up the TCP property on its own)
setprop service.adb.tcp.port 5555
setprop sys.usb.config mtp,adb
setprop ctl.restart adbd
echo "  adb requested; init.svc.adbd=$(getprop init.svc.adbd)" >> "$LOG"

# 4) watcher: re-assert both, for the life of the boot
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

# 5) keep adb alive with the screen idle
settings put global stay_on_while_plugged_in 3 2>>"$LOG"

# 6) RE-ARM: the engine moves UPDATE_APP_INSTALL_DIR -> _TMP after processing, so
#    recreate the driver for the next boot. This is what makes it persistent.
D=/storage/sdcard1/UPDATE_APP_INSTALL_DIR
mkdir -p "$D" 2>/dev/null
cp "$HOOKDIR/1_miko3.l" "$D/1_miko3.l" 2>>"$LOG"
echo "  re-armed" >> "$LOG"
