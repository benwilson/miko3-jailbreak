# com.miko.update_app — decompiled (key files)

Source APK: `/data/app/com.miko.update_app-Rhl5obbnUbEV-7-slFYtsQ==/base.apk`
Pulled to `recon/sources/` then decompiled with jadx.

Only the classes relevant to the OTA engine are kept here:

| File | Why |
|------|-----|
| `ProcessUpdates.java` | the whole update engine: unzip → `.l` listing → per-file install → commands |
| `farrayCopy.java` | model for `3_files.l` (`files[]`, `commands[]`) |
| `fileCopy.java` | one entry in `files[]` (`target`, `source`, `version`) |
| `FileUtils.java` | all path helpers (`getInternalStorage`, `getDownloadAppDir`, …) |

Decompile command:

```bash
adb pull /data/app/com.miko.update_app-Rhl5obbnUbEV-7-slFYtsQ==/base.apk /tmp/miko-update.apk
jadx -d /tmp/miko-update-decompiled /tmp/miko-update.apk
```

See `docs/update-mechanism.md` for the write-up.
