package com.miko.app_update.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import com.emotix.arya.app_utils.AppUtils;
import com.emotix.arya.app_utils.FileUtils;
import com.emotix.arya.app_utils.ZipCallback;
import com.emotix.arya.app_utils.crypto.KeyUtils;
import com.emotix.arya.comm.utils.mikoProperties;
import com.google.gson.Gson;
import com.miko.app_update.apis.APIS;
import com.miko.app_update.boot.BootCallback;
import com.miko.app_update.boot.BootloaderCallback;
import com.miko.app_update.boot.BootloaderLibrary;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class ProcessUpdates implements BootloaderCallback, ZipCallback {
    public static final String INSTALL_ACTION = "MIKO_CUSTOM_INSTALL_APK";
    private static String TAG = "UPDATE_APP";
    public static final String UNINSTALL_ACTION = "MIKO_CUSTOM_UNINSTALL_APK";
    public static boolean firmware_flashing_in_progress = false;
    public static boolean isNewFirmware = false;
    public static boolean launchedByFTUE = false;
    public static boolean launchedByLauncher = false;
    public static boolean launchedByService = false;
    public static boolean mockflag = false;
    public static String newFirmwareName = "gd_src.ba";
    public static String newFirmwareNameQ = "gdq_src.ba";
    public static boolean updateAppInstallation = false;
    public static boolean useShellCommands = false;
    public static byte[] version_text;
    String b1;
    private String botUserName;
    BootCallback callback;
    Context context;
    FileUtils futils;
    mikoProperties p;
    PackageInstaller.Session session;
    Handler handler1 = null;
    PackageInstaller.SessionCallback sessionCallback = new PackageInstaller.SessionCallback() { // from class: com.miko.app_update.update.ProcessUpdates.1
        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onCreated(int i) {
            Log.e("SessionCallback", "onCreated " + i);
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onBadgingChanged(int i) {
            Log.e("SessionCallback", "onBadgingChanged " + i);
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onActiveChanged(int i, boolean z) {
            Log.e("SessionCallback", "onActiveChanged " + i + " active " + z);
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onProgressChanged(int i, float f) {
            Log.e("SessionCallback", "onProgressChanged " + i + " progress " + f);
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onFinished(int i, boolean z) {
            Log.e("SessionCallback", "onFinished " + i + " success " + z);
            if (z) {
                ProcessUpdates.this.status = 2;
            } else {
                ProcessUpdates.this.status = 1;
                Log.e("SessionCallback", "install failed 1");
            }
        }
    };
    int currentUpdate = 0;
    int totalUpdates = 0;
    public int status = 0;
    KeyUtils kutils = new KeyUtils();
    public BootloaderLibrary b = new BootloaderLibrary();

    @Override // com.miko.app_update.boot.BootloaderCallback
    public void updateImagecallback() {
    }

    @Override // com.miko.app_update.boot.BootloaderCallback
    public void updateProgramCallback() {
    }

    public Context getContext() {
        return this.context;
    }

    public ProcessUpdates(BootCallback bootCallback, Context context, String str) {
        boolean z = false;
        this.callback = bootCallback;
        this.context = context;
        this.p = mikoProperties.getInstance(context);
        try {
            String botName = this.p.getBotName();
            com.emotix.arya.app_utils.Log.e("Omkar", "Botname = " + botName);
            this.botUserName = botName.substring(10, 19);
            if (this.botUserName.startsWith("M3J") || this.botUserName.startsWith("M3Q")) {
                z = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        isNewFirmware = this.b.init(this, context, z);
        this.b1 = str;
    }

    public boolean isIsNewFirmware() {
        return isNewFirmware;
    }

    public void UpdateSilent(File file, String str) {
        int iCreateSession;
        OutputStream outputStreamOpenWrite;
        FileInputStream fileInputStream;
        byte[] bArr;
        int i;
        try {
            if (useShellCommands) {
                com.emotix.arya.app_utils.Log.e(TAG, "Installing copying " + file.getAbsolutePath());
                Process processExec = Runtime.getRuntime().exec("su");
                DataOutputStream dataOutputStream = new DataOutputStream(processExec.getOutputStream());
                dataOutputStream.writeBytes("cp " + file.getAbsolutePath() + " /data/local/tmp/ \n");
                dataOutputStream.flush();
                dataOutputStream.close();
                processExec.waitFor();
                com.emotix.arya.app_utils.Log.e(TAG, "Input Stream Reading for copying");
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(processExec.getInputStream()));
                while (true) {
                    String line = bufferedReader.readLine();
                    if (line == null) {
                        break;
                    }
                    com.emotix.arya.app_utils.Log.e("InputStream", line + "\n");
                }
                com.emotix.arya.app_utils.Log.e(TAG, "Error Stream Reading for copying");
                BufferedReader bufferedReader2 = new BufferedReader(new InputStreamReader(processExec.getErrorStream()));
                String str2 = "";
                while (true) {
                    String line2 = bufferedReader2.readLine();
                    if (line2 == null) {
                        break;
                    }
                    com.emotix.arya.app_utils.Log.e("ErrorStream", line2 + "\n");
                    str2 = str2 + line2;
                }
                if (str2 != null && str2.trim().length() > 1) {
                    this.status = 1;
                    Log.e("SessionCallback", "install failed 3");
                    return;
                }
                Process processExec2 = Runtime.getRuntime().exec("su");
                DataOutputStream dataOutputStream2 = new DataOutputStream(processExec2.getOutputStream());
                dataOutputStream2.writeBytes("pm install -r -t /data/local/tmp/" + file.getName() + "\n");
                dataOutputStream2.flush();
                dataOutputStream2.close();
                processExec2.waitFor();
                com.emotix.arya.app_utils.Log.e(TAG, "Input Stream Reading for " + file.getName());
                BufferedReader bufferedReader3 = new BufferedReader(new InputStreamReader(processExec2.getInputStream()));
                String str3 = "";
                while (true) {
                    String line3 = bufferedReader3.readLine();
                    if (line3 == null) {
                        break;
                    }
                    com.emotix.arya.app_utils.Log.e("InputStream", line3 + "\n");
                    str3 = str3 + line3;
                }
                com.emotix.arya.app_utils.Log.e(TAG, "Error Stream Reading for " + file.getName());
                BufferedReader bufferedReader4 = new BufferedReader(new InputStreamReader(processExec2.getErrorStream()));
                String str4 = "";
                while (true) {
                    String line4 = bufferedReader4.readLine();
                    if (line4 == null) {
                        break;
                    }
                    com.emotix.arya.app_utils.Log.e("ErrorStream", line4 + "\n");
                    str4 = str4 + line4;
                }
                Process processExec3 = Runtime.getRuntime().exec("su");
                DataOutputStream dataOutputStream3 = new DataOutputStream(processExec3.getOutputStream());
                dataOutputStream3.writeBytes("rm /data/local/tmp/" + file.getName() + "\n");
                dataOutputStream3.flush();
                dataOutputStream3.close();
                processExec3.waitFor();
                if (str4 != null && str4.trim().length() > 1) {
                    com.emotix.arya.app_utils.Log.e(TAG, "Installation Error 1");
                    this.status = 1;
                    Log.e("SessionCallback", "install failed 4");
                    return;
                } else if (str3 != null && str3.trim().length() > 1 && str3.contains("Success")) {
                    com.emotix.arya.app_utils.Log.e(TAG, "Installation Successful");
                    this.status = 2;
                    return;
                } else {
                    com.emotix.arya.app_utils.Log.e(TAG, "Installation Error 2");
                    Log.e("SessionCallback", "install failed 5");
                    this.status = 1;
                    return;
                }
            }
            if (!file.exists()) {
                return;
            }
            HandlerThread handlerThread = new HandlerThread("xyz");
            handlerThread.start();
            Looper looper = handlerThread.getLooper();
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            PackageInstaller packageInstaller = getContext().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(1);
            sessionParams.setAppPackageName(str);
            if (this.handler1 == null) {
                this.handler1 = new Handler(looper);
                packageInstaller.registerSessionCallback(this.sessionCallback, this.handler1);
            }
            try {
                if (this.session != null) {
                    this.session.close();
                }
                while (true) {
                    int i2 = fileInputStream.read(bArr);
                    if (i2 != -1) {
                        i += i2;
                        outputStreamOpenWrite.write(bArr, 0, i2);
                    } else {
                        Log.e(TAG, "total size = " + i);
                        this.session.fsync(outputStreamOpenWrite);
                        outputStreamOpenWrite.close();
                        fileInputStream.close();
                        this.session.commit(PendingIntent.getBroadcast(this.context, iCreateSession, new Intent(INSTALL_ACTION), 0).getIntentSender());
                        return;
                    }
                }
            } catch (Exception unused) {
            }
            iCreateSession = packageInstaller.createSession(sessionParams);
            this.session = packageInstaller.openSession(iCreateSession);
            outputStreamOpenWrite = this.session.openWrite(str, 0L, -1L);
            fileInputStream = new FileInputStream(new File(file.getAbsolutePath()));
            bArr = new byte[65536];
            i = 0;
        } catch (Exception e) {
            e.printStackTrace();
            com.emotix.arya.app_utils.Log.i(TAG, "Exception: " + e.getMessage());
        }
    }

    public void UpdateSilent2(String str) {
        uninstallApkDefault(str);
    }

    public void uninstallApkDefault(String str) {
        try {
            if (useShellCommands) {
                com.emotix.arya.app_utils.Log.e(TAG, "Uninstalling copying " + str);
                Process processExec = Runtime.getRuntime().exec("su");
                DataOutputStream dataOutputStream = new DataOutputStream(processExec.getOutputStream());
                dataOutputStream.writeBytes("pm uninstall " + str + " \n");
                dataOutputStream.flush();
                dataOutputStream.close();
                processExec.waitFor();
                com.emotix.arya.app_utils.Log.e(TAG, "Input Stream Reading for uninstall");
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(processExec.getInputStream()));
                while (true) {
                    String line = bufferedReader.readLine();
                    if (line == null) {
                        break;
                    }
                    com.emotix.arya.app_utils.Log.e("InputStream", line + "\n");
                }
                com.emotix.arya.app_utils.Log.e(TAG, "Error Stream Reading for uninstall");
                BufferedReader bufferedReader2 = new BufferedReader(new InputStreamReader(processExec.getErrorStream()));
                while (true) {
                    String line2 = bufferedReader2.readLine();
                    if (line2 == null) {
                        return;
                    }
                    com.emotix.arya.app_utils.Log.e("ErrorStream", line2 + "\n");
                }
            } else {
                APIS.dynamic_analytics_api(TAG, "calling package installer UNINSTALL_ACTION" + str);
                getContext().getPackageManager().getPackageInstaller().uninstall(str, PendingIntent.getBroadcast(this.context, 0, new Intent(UNINSTALL_ACTION), 0).getIntentSender());
            }
        } catch (Exception e) {
            this.status = 2;
            APIS.dynamic_analytics_api(TAG, "exception in package installer UNINSTALL_ACTION" + e.getMessage());
            Log.e(TAG, "Uninstall Exception = " + e.getMessage());
        }
    }

    public void uninstallApkDefault(Context context, String str, String str2) {
        try {
            new Thread() { // from class: com.miko.app_update.update.ProcessUpdates.2
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    ProcessUpdates.this.status = 2;
                }
            }.start();
        } catch (Exception e) {
            e.printStackTrace();
            this.status = 1;
            Log.e("SessionCallback", "install failed 6");
        }
    }

    @Override // com.miko.app_update.boot.BootloaderCallback
    public void errorCallBack(boolean z) {
        if (z) {
            if (!launchedByLauncher) {
                APIS.dynamic_analytics_api(TAG, "bootloader update successful");
            }
            this.status = 2;
            this.callback.bootCallback(true);
            return;
        }
        if (!launchedByLauncher) {
            APIS.dynamic_analytics_api(TAG, "failure in bootloader update");
        }
        this.callback.bootCallback(false);
        this.status = 1;
        Log.e("SessionCallback", "install failed 7");
    }

    @Override // com.miko.app_update.boot.BootloaderCallback
    public void connectSuccess() {
        if (!this.b.sendLoopBack()) {
            APIS.dynamic_analytics_api(TAG, "error in connecting to robot");
            this.callback.bootCallback(false);
        } else {
            APIS.dynamic_analytics_api(TAG, "successful connecting to robot");
        }
    }

    @Override // com.miko.app_update.boot.BootloaderCallback
    public void progressStatusCallBack(int i) {
        this.callback.downloadStatus(this.totalUpdates, this.currentUpdate, i);
    }

    @Override // com.emotix.arya.app_utils.ZipCallback
    public void progressCallback(int i, int i2, int i3) {
        HashMap map = new HashMap();
        map.put("total", Integer.valueOf(i));
        map.put("current", Integer.valueOf(i2));
        map.put(NotificationCompat.CATEGORY_PROGRESS, Integer.valueOf((i2 * 100) / i));
        int i4 = (int) ((((double) i2) * 100.0d) / ((double) i));
        String strSerializeMap = AppUtils.SerializeMap(map);
        if (i4 % 10 == 0) {
            APIS.dynamic_analytics_api(TAG, "UNZIP_WAIT_OPERATION", "value", strSerializeMap);
        }
        this.callback.downloadStatus(this.totalUpdates, this.currentUpdate, i3);
    }

    private class MyPackageDeleteObserver extends IPackageDeleteObserver.Stub {
        String action;
        Context cxt;
        String pkname;

        public MyPackageDeleteObserver(Context context, String str, String str2) {
            this.cxt = context;
            this.action = str;
            this.pkname = str2;
        }

        public void packageDeleted(String str, int i) {
            com.emotix.arya.app_utils.Log.d(ProcessUpdates.TAG, "returnCode = " + i + ",action:" + this.action + "packageName:" + str + ",pkname:" + this.pkname);
            if (i == 1) {
                com.emotix.arya.app_utils.Log.e(ProcessUpdates.TAG, "Deletion of package successful " + str);
                ProcessUpdates.this.status = 2;
                return;
            }
            com.emotix.arya.app_utils.Log.e(ProcessUpdates.TAG, "error in package deletetion " + str);
            ProcessUpdates.this.status = 1;
            Log.e("SessionCallback", "install failed 8");
        }
    }

    class MyPackageInstallObserver extends IPackageInstallObserver.Stub {
        MyPackageInstallObserver() {
        }

        public void packageInstalled(final String str, final int i) {
            com.emotix.arya.app_utils.Log.e("omkar", "Install Result = " + str + "::" + i);
            new Thread(new Runnable() { // from class: com.miko.app_update.update.ProcessUpdates.MyPackageInstallObserver.1
                @Override // java.lang.Runnable
                public void run() {
                    boolean z = i == 1;
                    com.emotix.arya.app_utils.Log.e("omkar", "Install Success " + z);
                    com.emotix.arya.app_utils.Log.e(ProcessUpdates.TAG, "completed installing the app " + str);
                    if (z) {
                        AppUtils.grantPermissions(ProcessUpdates.this.getContext(), str, AppUtils.getPermissions());
                        com.emotix.arya.app_utils.Log.e(ProcessUpdates.TAG, "completed granting permissions to the app " + str);
                        ProcessUpdates.this.status = 2;
                        return;
                    }
                    ProcessUpdates.this.status = 1;
                    Log.e("SessionCallback", "install failed 9");
                }
            }).start();
        }
    }

    public void updateApp(File file, String str, int i) throws IOException {
        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "installing package " + str + ":" + file.getAbsolutePath());
        if (i == 0) {
            if (file.exists()) {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "UpdateSilent " + str + ":" + file.getAbsolutePath());
                UpdateSilent(file, str);
            } else {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "file not found " + str + ":" + file.getAbsolutePath());
                this.status = 1;
                Log.e("SessionCallback", "install failed 10");
            }
        }
        if (i == 2) {
            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "uninstall operation " + str + ":" + file.getAbsolutePath());
            UpdateSilent2(str);
        }
    }

    public boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT < 23 || getContext().checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == 0) {
            return true;
        }
        APIS.dynamic_analytics_api(TAG, "permissiob is " + getContext().checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE"));
        return false;
    }

    public boolean processFiles(File file) throws IOException {
        farrayCopy farraycopy;
        long j;
        long j2;
        File file2;
        if (this.futils == null) {
            this.futils = FileUtils.getInstance(this.context);
        }
        String extension = this.futils.getExtension(file.getAbsolutePath());
        if (extension == null) {
            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "file has no extension " + file.getAbsolutePath());
            return true;
        }
        String baseName = this.futils.getBaseName(file.getName());
        if (baseName == null) {
            file.getName();
        } else {
            baseName.substring(2);
        }
        if (extension.equals("l")) {
            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "package installation listing file " + file.getAbsolutePath());
            long jCurrentTimeMillis = System.currentTimeMillis();
            long jCurrentTimeMillis2 = System.currentTimeMillis();
            try {
                Gson gson = new Gson();
                String json = this.futils.readJSON(file.getAbsolutePath());
                if (json != null) {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", json);
                    com.emotix.arya.app_utils.Log.e(TAG, "read the json string from file " + json + ":" + file.getAbsolutePath());
                    farrayCopy farraycopy2 = (farrayCopy) gson.fromJson(json, farrayCopy.class);
                    if (farraycopy2 == null) {
                        return false;
                    }
                    if (farraycopy2.files == null) {
                        return true;
                    }
                    this.totalUpdates = farraycopy2.files.size();
                    if (farraycopy2.commands != null) {
                        this.totalUpdates++;
                    }
                    int i = 0;
                    while (i < farraycopy2.files.size()) {
                        int i2 = i + 1;
                        this.currentUpdate = i2;
                        fileCopy filecopy = farraycopy2.files.get(i);
                        int processPercentage = getProcessPercentage(i, farraycopy2.files.size());
                        int processPercentage2 = getProcessPercentage(i2, farraycopy2.files.size());
                        HashMap map = new HashMap();
                        map.put("currentUpdate", Integer.valueOf(this.currentUpdate));
                        map.put("prevUpdate", Integer.valueOf(processPercentage));
                        map.put("totalUpdate", Integer.valueOf(processPercentage2));
                        StringBuilder sb = new StringBuilder();
                        sb.append("");
                        farrayCopy farraycopy3 = farraycopy2;
                        sb.append(jCurrentTimeMillis2 - jCurrentTimeMillis);
                        map.put("elapsed_time", sb.toString());
                        APIS.dynamic_analytics_api(TAG, "UPPDATE_PROGRESS", "value", AppUtils.SerializeMap(map));
                        if (this.futils.getExtension(filecopy.getSource()).equals("z")) {
                            this.callback.downloadStatus(processPercentage, 0, -30);
                            File file3 = new File(Environment.getExternalStorageDirectory(), filecopy.getTarget());
                            File file4 = new File(file.getParent(), filecopy.getSource());
                            HashMap map2 = new HashMap();
                            map2.put("source", file4.getAbsolutePath());
                            map2.put("target", file3.getAbsolutePath());
                            long jCurrentTimeMillis3 = System.currentTimeMillis();
                            APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_START", "value", AppUtils.SerializeMap(map2));
                            farraycopy = farraycopy3;
                            String strUnzipUsingLibray1 = this.futils.unzipUsingLibray1(file4.getAbsolutePath(), file3.getAbsolutePath(), this, processPercentage, processPercentage2);
                            if (strUnzipUsingLibray1 == "0") {
                                this.callback.downloadStatus(processPercentage2, 0, -30);
                                long jCurrentTimeMillis4 = System.currentTimeMillis() - jCurrentTimeMillis3;
                                HashMap map3 = new HashMap();
                                map3.put("source", file4.getAbsolutePath());
                                map3.put("target", file3.getAbsolutePath());
                                map3.put("operation", "data_install");
                                map3.put("time", Long.valueOf(jCurrentTimeMillis4));
                                map3.put(NotificationCompat.CATEGORY_STATUS, strUnzipUsingLibray1);
                                map3.put("count", 1);
                                APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_SUCCESS", "value", AppUtils.SerializeMap(map3));
                                j = jCurrentTimeMillis;
                            } else {
                                long jCurrentTimeMillis5 = System.currentTimeMillis() - jCurrentTimeMillis3;
                                HashMap map4 = new HashMap();
                                map4.put("source", file4.getAbsolutePath());
                                map4.put("target", file3.getAbsolutePath());
                                map4.put("operation", "data_install");
                                map4.put("time", Long.valueOf(jCurrentTimeMillis5));
                                map4.put(NotificationCompat.CATEGORY_STATUS, strUnzipUsingLibray1);
                                map4.put("count", 1);
                                APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_FAIL", "value", AppUtils.SerializeMap(map4));
                                return false;
                            }
                        } else {
                            farraycopy = farraycopy3;
                            if (this.futils.getExtension(filecopy.getSource()).equals("ia")) {
                                this.callback.downloadStatus(processPercentage, 0, -31);
                                this.status = 0;
                                File file5 = new File(file.getParent(), filecopy.getSource());
                                HashMap map5 = new HashMap();
                                map5.put("source", file5.getAbsolutePath());
                                map5.put("target", filecopy.getTarget());
                                String strSerializeMap = AppUtils.SerializeMap(map5);
                                long jCurrentTimeMillis6 = System.currentTimeMillis();
                                APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_START", "value", strSerializeMap);
                                updateApp(file5, filecopy.getTarget(), 0);
                                int i3 = 0;
                                while (this.status == 0) {
                                    StringBuilder sb2 = new StringBuilder();
                                    sb2.append("");
                                    long j3 = jCurrentTimeMillis;
                                    sb2.append(System.currentTimeMillis() - jCurrentTimeMillis6);
                                    map5.put("time", sb2.toString());
                                    map5.put("count", "" + i3);
                                    String strSerializeMap2 = AppUtils.SerializeMap(map5);
                                    APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_WAIT", "LOGS", strSerializeMap2);
                                    Thread.sleep(1000L);
                                    if (i3 > 500) {
                                        APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_TIMEOUT", "value", strSerializeMap2);
                                        return false;
                                    }
                                    i3++;
                                    jCurrentTimeMillis = j3;
                                }
                                j = jCurrentTimeMillis;
                                HashMap map6 = new HashMap();
                                map6.put("file", file5.getAbsolutePath());
                                map6.put("package", filecopy.getTarget());
                                map6.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis6));
                                map6.put("operation", "install");
                                map6.put(NotificationCompat.CATEGORY_STATUS, Integer.valueOf(this.status));
                                map6.put("count", Integer.valueOf(i3));
                                String strSerializeMap3 = AppUtils.SerializeMap(map6);
                                if (this.status == 1) {
                                    APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_FAIL", "value", strSerializeMap3);
                                    return false;
                                }
                                if (this.status == 2) {
                                    APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_SUCCESS", "value", strSerializeMap3);
                                    try {
                                        AppUtils.grantPermissions(getContext(), filecopy.getTarget(), AppUtils.getPermissions());
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    HashMap map7 = new HashMap();
                                    map7.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis6));
                                    map7.put("operation", "install");
                                    String strSerializeMap4 = AppUtils.SerializeMap(map7);
                                    APIS.dynamic_analytics_api(TAG, "INSTALL_OPERATION_SUCCESS1", "value", "" + strSerializeMap4);
                                }
                                this.callback.downloadStatus(processPercentage2, 0, -31);
                            } else {
                                j = jCurrentTimeMillis;
                                if (this.futils.getExtension(filecopy.getSource()).equals("ra")) {
                                    HashMap map8 = new HashMap();
                                    map8.put("source", filecopy.getSource());
                                    map8.put("target", filecopy.getTarget());
                                    String strSerializeMap5 = AppUtils.SerializeMap(map8);
                                    long jCurrentTimeMillis7 = System.currentTimeMillis();
                                    APIS.dynamic_analytics_api(TAG, "UNINSTALL_OPERATION_START", "value", strSerializeMap5);
                                    this.callback.downloadStatus(processPercentage, 0, -31);
                                    this.status = 0;
                                    updateApp(new File(file.getParent(), filecopy.getSource()), filecopy.getTarget(), 2);
                                    int i4 = 0;
                                    while (this.status == 0) {
                                        map8.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis7));
                                        map8.put("count", "" + i4);
                                        String strSerializeMap6 = AppUtils.SerializeMap(map8);
                                        APIS.dynamic_analytics_api(TAG, "UNINSTALL_OPERATION_WAIT", "value", strSerializeMap6);
                                        Thread.sleep(1000L);
                                        if (i4 > 45) {
                                            APIS.dynamic_analytics_api(TAG, "UNINSTALL_OPERATION_TIMEOUT", "value", strSerializeMap6);
                                            return false;
                                        }
                                        i4++;
                                    }
                                    HashMap map9 = new HashMap();
                                    map9.put("file", filecopy.getSource());
                                    map9.put("package", filecopy.getTarget());
                                    map9.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis7));
                                    map9.put("operation", "uninstall");
                                    map9.put(NotificationCompat.CATEGORY_STATUS, Integer.valueOf(this.status));
                                    map9.put("count", Integer.valueOf(i4));
                                    APIS.dynamic_analytics_api(TAG, "UNINSTALL_OPERATION_SUCCESS", "value", AppUtils.SerializeMap(map9));
                                    this.status = 2;
                                    this.callback.downloadStatus(processPercentage2, 0, -31);
                                } else if (this.futils.getExtension(filecopy.getSource()).equals("ba")) {
                                    AppUtils.closeApp("com.example.root.serviceexam");
                                    String source = filecopy.getSource();
                                    String target = filecopy.getTarget();
                                    if (isNewFirmware) {
                                        if (this.botUserName.startsWith("M3Q")) {
                                            source = newFirmwareNameQ;
                                            target = newFirmwareNameQ;
                                        } else {
                                            source = newFirmwareName;
                                            target = newFirmwareName;
                                        }
                                    }
                                    HashMap map10 = new HashMap();
                                    map10.put("source", source);
                                    map10.put("target", target);
                                    String strSerializeMap7 = AppUtils.SerializeMap(map10);
                                    long jCurrentTimeMillis8 = System.currentTimeMillis();
                                    Log.i(TAG, strSerializeMap7);
                                    APIS.dynamic_analytics_api(TAG, "FIRMWARE_INSTALL_OPERATION_START", "value", strSerializeMap7);
                                    this.callback.downloadStatus(processPercentage, 0, -32);
                                    this.status = 0;
                                    boolean bluetooth = this.p.getBluetooth();
                                    File file6 = new File(file.getParent(), source);
                                    firmware_flashing_in_progress = true;
                                    try {
                                        File file7 = new File("/sdcard/miko_recovery/");
                                        if (!file7.exists()) {
                                            file7.mkdirs();
                                        }
                                        if (isNewFirmware) {
                                            if (this.botUserName.startsWith("M3Q")) {
                                                file2 = new File("/sdcard/miko_recovery/" + newFirmwareNameQ);
                                            } else {
                                                file2 = new File("/sdcard/miko_recovery/" + newFirmwareName);
                                            }
                                        } else {
                                            file2 = new File("/sdcard/miko_recovery/src.ba");
                                        }
                                        FileInputStream fileInputStream = new FileInputStream(file6);
                                        FileOutputStream fileOutputStream = new FileOutputStream(file2);
                                        copyFile(fileInputStream, fileOutputStream);
                                        fileInputStream.close();
                                        fileOutputStream.flush();
                                        fileOutputStream.close();
                                    } catch (Exception e2) {
                                        APIS.dynamic_analytics_api(TAG, "FIRMWARE_RECOVERY_FILE_ERROR", "EXCEPTION", AppUtils.getException(e2));
                                    }
                                    if (bluetooth) {
                                        this.b.start_program_flash(file6.getAbsolutePath(), this.b1);
                                    } else {
                                        this.b.start_program_flash(file6.getAbsolutePath(), null);
                                    }
                                    int i5 = 0;
                                    while (firmware_flashing_in_progress) {
                                        StringBuilder sb3 = new StringBuilder();
                                        sb3.append("");
                                        long j4 = jCurrentTimeMillis2;
                                        sb3.append(System.currentTimeMillis() - jCurrentTimeMillis8);
                                        map10.put("time", sb3.toString());
                                        map10.put("count", "" + i5);
                                        String strSerializeMap8 = AppUtils.SerializeMap(map10);
                                        APIS.dynamic_analytics_api(TAG, "FIRMWARE_INSTALL_OPERATION_WAIT", "LOGS", strSerializeMap8);
                                        try {
                                            Thread.sleep(1000L);
                                        } catch (InterruptedException e3) {
                                            e3.printStackTrace();
                                        }
                                        if (i5 > 1800) {
                                            APIS.dynamic_analytics_api(TAG, "FIRMWARE_INSTALL_OPERATION_TIMEOUT", "value", strSerializeMap8);
                                            return false;
                                        }
                                        i5++;
                                        jCurrentTimeMillis2 = j4;
                                    }
                                    j2 = jCurrentTimeMillis2;
                                    HashMap map11 = new HashMap();
                                    map11.put("file", file6.getAbsolutePath());
                                    map11.put("package", target);
                                    map11.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis8));
                                    map11.put("operation", "firmware");
                                    map11.put(NotificationCompat.CATEGORY_STATUS, Boolean.valueOf(firmware_flashing_in_progress));
                                    map11.put("count", Integer.valueOf(i5));
                                    String strSerializeMap9 = AppUtils.SerializeMap(map11);
                                    this.callback.downloadStatus(processPercentage2, 0, -31);
                                    if (firmware_flashing_in_progress) {
                                        APIS.dynamic_analytics_api(TAG, "FIRMWARE_INSTALL_OPERATION_FAIL", "value", strSerializeMap9);
                                        return false;
                                    }
                                    APIS.dynamic_analytics_api(TAG, "FIRMWARE_INSTALL_OPERATION_SUCCESS", "value", strSerializeMap9);
                                } else {
                                    j2 = jCurrentTimeMillis2;
                                    File file8 = new File(Environment.getExternalStorageDirectory(), filecopy.getTarget());
                                    File file9 = new File(file.getParent(), filecopy.getSource());
                                    HashMap map12 = new HashMap();
                                    map12.put("source", file9.getAbsolutePath());
                                    map12.put("target", file8.getAbsolutePath());
                                    String strSerializeMap10 = AppUtils.SerializeMap(map12);
                                    long jCurrentTimeMillis9 = System.currentTimeMillis();
                                    APIS.dynamic_analytics_api(TAG, "FILECOPY_OPERATION_START", "value", strSerializeMap10);
                                    APIS.dynamic_analytics_api(TAG, "asdf copying the files from " + file9.getAbsolutePath() + ":" + file8.getAbsolutePath());
                                    try {
                                        this.futils.copyDirectoryOneLocationToAnotherLocation(file9, file8);
                                        HashMap map13 = new HashMap();
                                        map13.put("file", file9.getAbsolutePath());
                                        map13.put("package", file8.getAbsolutePath());
                                        map13.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis9));
                                        map13.put("operation", "install");
                                        map13.put(NotificationCompat.CATEGORY_STATUS, Integer.valueOf(this.status));
                                        map13.put("count", 1);
                                        APIS.dynamic_analytics_api(TAG, "FILECOPY_OPERATION_SUCCESS", "value", AppUtils.SerializeMap(map13));
                                    } catch (Exception e4) {
                                        APIS.dynamic_analytics_api(TAG, "FILECOPY_OPERATION_FAILURE", "LOGS", AppUtils.getException(e4));
                                    }
                                }
                                i = i2;
                                farraycopy2 = farraycopy;
                                jCurrentTimeMillis = j;
                                jCurrentTimeMillis2 = j2;
                            }
                        }
                        j2 = jCurrentTimeMillis2;
                        i = i2;
                        farraycopy2 = farraycopy;
                        jCurrentTimeMillis = j;
                        jCurrentTimeMillis2 = j2;
                    }
                    farrayCopy farraycopy4 = farraycopy2;
                    if (farraycopy4.commands == null) {
                        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "COMPLETE");
                        return true;
                    }
                    this.currentUpdate++;
                    for (int i6 = 0; i6 < farraycopy4.commands.size(); i6++) {
                        HashMap map14 = new HashMap();
                        map14.put("source", farraycopy4.commands.get(i6));
                        map14.put("target", farraycopy4.commands.get(i6));
                        String strSerializeMap11 = AppUtils.SerializeMap(map14);
                        long jCurrentTimeMillis10 = System.currentTimeMillis();
                        APIS.dynamic_analytics_api(TAG, "COMMAND_OPERATION_START", "value", strSerializeMap11);
                        String strRunCommands1 = AppUtils.runCommands1(farraycopy4.commands.get(i6));
                        if (strRunCommands1 == null || strRunCommands1.contains("error in running commands")) {
                            HashMap map15 = new HashMap();
                            map15.put("source", farraycopy4.commands.get(i6));
                            map15.put("target", farraycopy4.commands.get(i6));
                            map15.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis10));
                            map15.put("operation", "install");
                            map15.put(NotificationCompat.CATEGORY_STATUS, Integer.valueOf(this.status));
                            map15.put("count", 1);
                            map15.put("output", "");
                            APIS.dynamic_analytics_api(TAG, "COMMAND_OPERATION_FAIL", "value", AppUtils.SerializeMap(map15));
                        } else {
                            HashMap map16 = new HashMap();
                            map16.put("source", farraycopy4.commands.get(i6));
                            map16.put("target", farraycopy4.commands.get(i6));
                            map16.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis10));
                            map16.put("operation", "commands");
                            map16.put(NotificationCompat.CATEGORY_STATUS, Integer.valueOf(this.status));
                            map16.put("count", 1);
                            map16.put("output", strRunCommands1);
                            APIS.dynamic_analytics_api(TAG, "COMMAND_OPERATION_SUCCESS", "value", AppUtils.SerializeMap(map16));
                        }
                    }
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "COMPLETE");
                    return true;
                }
                this.callback.downloadStatus(0, 0, -29);
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "EXCEPTION", "error in reading the file");
                Log.e("SessionCallback", "install failed 2");
                this.status = 1;
                return false;
            } catch (Exception e5) {
                this.callback.downloadStatus(0, 0, -29);
                e5.printStackTrace();
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "EXCEPTION", AppUtils.getException(e5));
                return false;
            }
        }
        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "not package listing file " + file.getAbsolutePath());
        return true;
    }

    public boolean readApps(File file) {
        Gson gson = new Gson();
        String json = this.futils.readJSON(file.getAbsolutePath());
        boolean z = true;
        if (json != null) {
            APIS.dynamic_analytics_api(TAG, "read the json string from file " + json + ":" + file.getAbsolutePath());
            farrayCopy farraycopy = (farrayCopy) gson.fromJson(json, farrayCopy.class);
            if (farraycopy == null || farraycopy.files == null) {
                return false;
            }
            for (int i = 0; i < farraycopy.files.size(); i++) {
                fileCopy filecopy = farraycopy.files.get(i);
                if (this.futils.getExtension(filecopy.getSource()).equals("apk")) {
                    String baseName = this.futils.getBaseName(filecopy.getSource());
                    File file2 = new File(baseName);
                    String parent = file2.getParent();
                    String name = file2.getName();
                    APIS.dynamic_analytics_api(TAG, "reading the files as :" + baseName + ":" + name + ":" + parent);
                    boolean zPackageExists = AppUtils.packageExists(getContext(), parent);
                    String str = TAG;
                    StringBuilder sb = new StringBuilder();
                    sb.append("checking if app exists ");
                    sb.append(zPackageExists);
                    APIS.dynamic_analytics_api(str, sb.toString());
                    if (!zPackageExists) {
                        z = zPackageExists;
                    }
                }
            }
        }
        return z;
    }

    public boolean launchApps1(File file, boolean z) {
        try {
            APIS.dynamic_analytics_api(TAG, "processing director " + file.getAbsoluteFile());
            if (!file.exists()) {
                return false;
            }
            boolean apps = readApps(file);
            APIS.dynamic_analytics_api(TAG, "checkApps  launchApps1 " + apps + ":" + z);
            if (apps) {
                APIS.dynamic_analytics_api(TAG, "checkApps  launchApps1 exists`" + apps + ":" + z);
            } else {
                APIS.dynamic_analytics_api(TAG, "entering recovery mode");
            }
            return false;
        } catch (Exception unused) {
            APIS.dynamic_analytics_api(TAG, "error in launchApps");
            return false;
        }
    }

    /* JADX WARN: Code duplicated, block: B:13:0x002e  */
    /* JADX WARN: Code duplicated, block: B:15:0x0036  */
    public boolean checkApps(int i) {
        boolean z;
        if (i == 0) {
            APIS.dynamic_analytics_api(TAG, "only check apps");
        } else {
            if (i == 1) {
                APIS.dynamic_analytics_api(TAG, "check and launch apps");
                z = true;
            } else if (i == 2) {
                APIS.dynamic_analytics_api(TAG, "receovery mode");
            }
            if (this.futils.checkProperties(getContext())) {
                return !launchApps1(new File(this.futils.getExternalStorage(getContext()), "apps.json"), z) || launchApps1(new File(this.futils.getInternalStorage(getContext()), "apps.json"), z);
            }
            APIS.dynamic_analytics_api(TAG, "miko properties file is not present");
            return false;
        }
        z = false;
        if (this.futils.checkProperties(getContext())) {
            APIS.dynamic_analytics_api(TAG, "miko properties file is not present");
            return false;
        }
        if (!launchApps1(new File(this.futils.getExternalStorage(getContext()), "apps.json"), z)) {
            return true;
        }
    }

    public int getTestFlag() {
        String propValueLocal = mikoProperties.getInstance(this.context).getPropValueLocal("TEST_FLAG");
        APIS.dynamic_analytics_api(TAG, "getTestFlag() " + propValueLocal);
        try {
            return Integer.parseInt(propValueLocal);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public synchronized void processInstall() {
        if (launchedByLauncher) {
            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "launchedByLauncher");
            this.futils = FileUtils.getInstance(getContext());
            if (updateAppInstallation) {
                File updateAppInstallDirectory = this.futils.getUpdateAppInstallDirectory(getContext());
                if (!updateAppInstallDirectory.exists()) {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "UPDATE_APP_INSTALL_DIR does not exist " + updateAppInstallDirectory.getAbsolutePath());
                    this.callback.downloadStatus(0, 0, -22);
                } else {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "UPDATE_APP_INSTALL_DIR found " + updateAppInstallDirectory.getAbsolutePath());
                    this.callback.downloadStatus(this.totalUpdates, this.currentUpdate, 100);
                    long jCurrentTimeMillis = System.currentTimeMillis();
                    HashMap map = new HashMap();
                    map.put("operation", "processinstall");
                    map.put("launch", "launchedByLauncher");
                    map.put("delete", true);
                    map.put("modex", 2);
                    map.put("update_dir", updateAppInstallDirectory.getAbsolutePath());
                    APIS.dynamic_analytics_api(TAG, "UPDATE_START", "value", AppUtils.SerializeMap(map));
                    processInstall(updateAppInstallDirectory, true, 2);
                    HashMap map2 = new HashMap();
                    map2.put("operation", "processinstall");
                    map2.put("launch", "launchedByLauncher");
                    map2.put("update_dir", updateAppInstallDirectory.getAbsolutePath());
                    map2.put("delete", true);
                    map2.put("modex", 2);
                    map2.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis));
                    APIS.dynamic_analytics_api(TAG, "UPDATE_END", "value", AppUtils.SerializeMap(map2));
                }
            } else {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "launchedByLauncher");
                File externalStorage = this.futils.getExternalStorage(getContext());
                if (!externalStorage.exists()) {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "EXTERNAL STORAGE INSTALL PACKAGE IS NOT PRESENT " + externalStorage.getAbsolutePath());
                    this.callback.downloadStatus(0, 0, -22);
                } else {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "EXTERNAL STORAGE INSTALL PACKAGE IS PRESENT " + externalStorage.getAbsolutePath());
                    this.callback.downloadStatus(this.totalUpdates, this.currentUpdate, 100);
                    long jCurrentTimeMillis2 = System.currentTimeMillis();
                    HashMap map3 = new HashMap();
                    map3.put("operation", "processinstall");
                    map3.put("launch", "launchedByLauncher");
                    map3.put("update_dir", externalStorage.getAbsolutePath());
                    map3.put("delete", true);
                    map3.put("modex", 2);
                    APIS.dynamic_analytics_api(TAG, "UPDATE_START", "value", AppUtils.SerializeMap(map3));
                    processInstall(externalStorage, true, 2);
                    HashMap map4 = new HashMap();
                    map4.put("operation", "processinstall");
                    map4.put("launch", "launchedByLauncher");
                    map4.put("update_dir", externalStorage.getAbsolutePath());
                    map4.put("delete", true);
                    map4.put("modex", 2);
                    map4.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis2));
                    APIS.dynamic_analytics_api(TAG, "UPDATE_END", "value", AppUtils.SerializeMap(map4));
                }
            }
        } else {
            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "launchedByLauncher false");
            this.futils = FileUtils.getInstance(getContext());
            File file = new File(this.futils.getDownloadDir(getContext()), "APPS.zip");
            File downloadAppDir = this.futils.getDownloadAppDir(getContext());
            if (!downloadAppDir.exists()) {
                downloadAppDir.mkdirs();
            } else {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "Download dir " + downloadAppDir.getAbsolutePath() + ":" + downloadAppDir.exists());
            }
            if (file.exists()) {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "Download file " + file.getAbsolutePath() + ":" + file.exists());
                this.callback.downloadStatus(0, 0, -21);
                if (getTestFlag() == 0 || getTestFlag() == 1) {
                    long jCurrentTimeMillis3 = System.currentTimeMillis();
                    HashMap map5 = new HashMap();
                    map5.put("operation", "unzip");
                    String str = launchedByLauncher ? "launchedByLauncher" : "launchedByLauncher";
                    if (launchedByService) {
                        str = "launchedByService";
                    }
                    if (launchedByFTUE) {
                        str = "launchedByFTUE";
                    }
                    if (mockflag) {
                        str = "mockflag";
                    }
                    map5.put("launch", str);
                    map5.put("source", file.getAbsolutePath());
                    map5.put("target", downloadAppDir.getAbsolutePath());
                    map5.put("update_dir", downloadAppDir.getAbsolutePath());
                    APIS.dynamic_analytics_api(TAG, "UNZIP_START", "value", AppUtils.SerializeMap(map5));
                    String strUnzipUsingLibray1 = this.futils.unzipUsingLibray1(file.getAbsolutePath(), downloadAppDir.getAbsolutePath(), this, 0, 100);
                    if (strUnzipUsingLibray1.equals("0")) {
                        HashMap map6 = new HashMap();
                        map6.put("operation", "unzip");
                        map6.put("launch", "launchedByLauncher");
                        map6.put("source", file.getAbsolutePath());
                        map6.put("target", downloadAppDir.getAbsolutePath());
                        map6.put("update_dir", downloadAppDir.getAbsolutePath());
                        map6.put("status1", strUnzipUsingLibray1);
                        map6.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis3));
                        APIS.dynamic_analytics_api(TAG, "UNZIP_SUCCESS", "value", AppUtils.SerializeMap(map6));
                        processInstall(downloadAppDir, true, 2);
                    } else {
                        HashMap map7 = new HashMap();
                        map7.put("operation", "unzip");
                        map7.put("launch", "launchedByLauncher");
                        map7.put("source", file.getAbsolutePath());
                        map7.put("target", downloadAppDir.getAbsolutePath());
                        map7.put("update_dir", downloadAppDir.getAbsolutePath());
                        map7.put("status1", strUnzipUsingLibray1);
                        map7.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis3));
                        APIS.dynamic_analytics_api(TAG, "UNZIP_FAILURE", "value", AppUtils.SerializeMap(map7));
                        this.callback.downloadStatus(0, 0, -22);
                    }
                } else {
                    processInstall(downloadAppDir, true, 2);
                }
            } else {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "Download file missing " + file.getAbsolutePath() + ":" + file.exists());
                this.callback.downloadStatus(0, 0, -22);
            }
        }
    }

    public synchronized byte[] getVersionfile() {
        return version_text;
    }

    public synchronized void processInstall(File file, boolean z, int i) {
        boolean z2;
        int i2;
        File[] fileArrListFiles;
        boolean z3;
        this.futils = FileUtils.getInstance(getContext());
        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "installing the package " + file.getAbsolutePath() + ":" + z + ":" + i);
        int i3 = 0;
        try {
            if (file.exists()) {
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "install directory exists " + file.getAbsolutePath());
                this.kutils.initKeys(getContext());
                this.kutils.generateSaveKeys1(file.getAbsolutePath());
                this.callback.downloadStatus(0, 0, -1);
                String str = Environment.getExternalStorageDirectory() + "/klug/ftue/updateStatus.txt";
                File file2 = new File(file, "version.txt");
                if (file2.exists()) {
                    com.emotix.arya.app_utils.Log.e(TAG, "version file is found will update the records");
                    version_text = this.futils.readFile(file2.getAbsolutePath());
                    File file3 = new File(str);
                    HashMap map = new HashMap();
                    map.put("version_file", file2.getAbsolutePath());
                    map.put("version_text", version_text);
                    map.put("version_exists", Boolean.valueOf(file2.exists()));
                    map.put("update_file", str);
                    map.put("update_file_exists", Boolean.valueOf(file3.exists()));
                    APIS.dynamic_analytics_api(TAG, "VERSION_TEXT", "LOGS", AppUtils.SerializeMap(map));
                    if (file3.exists()) {
                        file3.delete();
                    }
                } else {
                    version_text = null;
                    File file4 = new File(str);
                    HashMap map2 = new HashMap();
                    map2.put("version_file", file2.getAbsolutePath());
                    map2.put("version_text", version_text);
                    map2.put("version_exists", Boolean.valueOf(file2.exists()));
                    map2.put("update_file", str);
                    map2.put("update_file_exists", Boolean.valueOf(file4.exists()));
                    APIS.dynamic_analytics_api(TAG, "VERSION_TEXT", "LOGS", AppUtils.SerializeMap(map2));
                    if (file4.exists()) {
                        file4.delete();
                    }
                }
                File file5 = new File(file, "encrypt.kf");
                File file6 = new File(file, "FILES");
                if (file5.exists()) {
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "encrypt mode " + file5.exists());
                    if (!file6.exists()) {
                        file6.mkdirs();
                    }
                    i2 = 1;
                } else {
                    i2 = 0;
                }
                ArrayList arrayList = new ArrayList();
                if (i2 == 0) {
                    fileArrListFiles = file.listFiles();
                } else {
                    fileArrListFiles = file6.listFiles();
                }
                if (fileArrListFiles != null) {
                    List listAsList = Arrays.asList(fileArrListFiles);
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "files to be prorcessed " + listAsList.toString());
                    for (int i4 = 0; i4 < listAsList.size(); i4++) {
                        File file7 = (File) listAsList.get(i4);
                        if (!file7.isDirectory() && !this.futils.getExtension(file7.getName()).equals("kf")) {
                            arrayList.add(file7);
                        }
                    }
                    if (arrayList == null) {
                        return;
                    }
                    if (arrayList != null && arrayList.size() <= 0) {
                        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "no files in install directoru ");
                        this.callback.downloadStatus(0, 0, -22);
                    }
                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "count of files " + arrayList.size());
                    Collections.sort(arrayList, new Comparator<File>() { // from class: com.miko.app_update.update.ProcessUpdates.3
                        @Override // java.util.Comparator
                        public int compare(File file8, File file9) {
                            int i5;
                            int i6;
                            int i7;
                            if (file8.getName().startsWith("enc_")) {
                                i5 = 0;
                            } else {
                                try {
                                    i5 = Integer.parseInt(file8.getName().substring(1));
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    i5 = -1;
                                }
                            }
                            if (file9.getName().startsWith("enc_")) {
                                i6 = 0;
                            } else {
                                try {
                                    i6 = Integer.parseInt(file9.getName().substring(1));
                                } catch (Exception e2) {
                                    e2.printStackTrace();
                                    i6 = -1;
                                }
                            }
                            if (i5 != -1) {
                                try {
                                    try {
                                        i5 = Integer.parseInt(file8.getName().substring(4, 5));
                                    } catch (Exception e3) {
                                        e3.printStackTrace();
                                        i5 = -1;
                                    }
                                } catch (Exception e4) {
                                    e4.printStackTrace();
                                    APIS.dynamic_analytics_api(ProcessUpdates.TAG, "PROCESSINSTALL", "EXCEPTION", AppUtils.getException(e4));
                                    return 0;
                                }
                            }
                            if (i6 != -1) {
                                try {
                                    i7 = Integer.parseInt(file9.getName().substring(4, 5));
                                } catch (Exception e5) {
                                    e5.printStackTrace();
                                    i7 = -1;
                                }
                            } else {
                                i7 = i6;
                            }
                            if (i5 > i7) {
                                return 1;
                            }
                            return (i5 != i7 && i5 < i7) ? -1 : 0;
                        }
                    });
                }
                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "start processing install package " + arrayList.size());
                this.callback.downloadStatus(0, 0, -2);
                int i5 = 0;
                while (true) {
                    if (i5 >= arrayList.size()) {
                        z3 = true;
                        break;
                    }
                    File file8 = (File) arrayList.get(i5);
                    if (!file8.isDirectory()) {
                        if (file8.getName().startsWith("enc")) {
                            if (i2 != 0) {
                                continue;
                            } else if (this.futils.getExtension(file8.getName()).equals("kf")) {
                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "file is not to be processed " + file8.getAbsolutePath());
                            } else {
                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "entering the decryption process" + file8.getAbsolutePath());
                                File file9 = new File(this.futils.getInstallDir(getContext()), file8.getName().substring(4));
                                boolean zDecryptFile = this.kutils.decryptFile(file8.getAbsolutePath(), file9.getAbsolutePath());
                                if (!zDecryptFile) {
                                    z3 = false;
                                    break;
                                }
                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "file is decryptFile file " + file9.getAbsolutePath() + ":" + zDecryptFile);
                            }
                        } else if (i2 == 1) {
                            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "encryptFile " + i2);
                            File file10 = new File(this.futils.getExternalStorage(getContext()), "ENC");
                            if (!file10.exists()) {
                                file10.mkdirs();
                            }
                            File file11 = new File(file10, "enc_" + file8.getName());
                            if (this.kutils.encryptFile(file8.getAbsolutePath(), file11.getAbsolutePath())) {
                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "encryption is success " + file8.getAbsolutePath() + ":" + file11.getAbsolutePath());
                            } else {
                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "encryption is success " + file8.getAbsolutePath() + ":" + file11.getAbsolutePath());
                                z3 = false;
                                break;
                            }
                        } else {
                            continue;
                        }
                    }
                    i5++;
                }
                this.callback.downloadStatus(0, 0, -3);
                if (i2 == 0 && z3) {
                    int i6 = 0;
                    while (true) {
                        if (i6 < arrayList.size()) {
                            File file12 = (File) arrayList.get(i6);
                            if (!file12.isDirectory()) {
                                if (!file12.getName().startsWith("enc")) {
                                    if (file12.exists()) {
                                        this.status = i3;
                                        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "processing the files  " + file12.getAbsolutePath());
                                        if (this.futils == null) {
                                            this.futils = FileUtils.getInstance(this.context);
                                        }
                                        String extension = this.futils.getExtension(file12.getAbsolutePath());
                                        if (extension != null && extension.equals("l")) {
                                            long jCurrentTimeMillis = System.currentTimeMillis();
                                            HashMap map3 = new HashMap();
                                            map3.put("operation", "processinstall");
                                            map3.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis));
                                            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_START", "value", AppUtils.SerializeMap(map3));
                                            boolean zProcessFiles = processFiles(file12);
                                            HashMap map4 = new HashMap();
                                            map4.put("operation", "processinstall");
                                            map4.put(NotificationCompat.CATEGORY_STATUS, Boolean.valueOf(zProcessFiles));
                                            map4.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis));
                                            String strSerializeMap = AppUtils.SerializeMap(map4);
                                            if (!zProcessFiles) {
                                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_FAIL", "value", strSerializeMap);
                                            } else {
                                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_SUCCESS", "value", strSerializeMap);
                                            }
                                        }
                                    } else {
                                        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "EXCEPTION", "files does not exist " + file12.getAbsolutePath());
                                    }
                                    z2 = false;
                                } else if (this.futils.getExtension(file12.getName()).equals("kf")) {
                                    APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "file is not to be processed" + file12.getAbsolutePath());
                                } else {
                                    File file13 = new File(this.futils.getInstallDir(getContext()), file12.getName().substring(4));
                                    if (file13.exists()) {
                                        APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL", "LOGS", "processing the files  " + file12.getAbsolutePath());
                                        if (this.futils == null) {
                                            this.futils = FileUtils.getInstance(this.context);
                                        }
                                        String extension2 = this.futils.getExtension(file12.getAbsolutePath());
                                        if (extension2 != null && extension2.equals("l")) {
                                            long jCurrentTimeMillis2 = System.currentTimeMillis();
                                            HashMap map5 = new HashMap();
                                            map5.put("operation", "processinstall");
                                            map5.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis2));
                                            APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_START", "value", AppUtils.SerializeMap(map5));
                                            this.status = 0;
                                            boolean zProcessFiles2 = processFiles(file13);
                                            HashMap map6 = new HashMap();
                                            map6.put("operation", "processinstall");
                                            map6.put(NotificationCompat.CATEGORY_STATUS, Boolean.valueOf(zProcessFiles2));
                                            map6.put("time", "" + (System.currentTimeMillis() - jCurrentTimeMillis2));
                                            String strSerializeMap2 = AppUtils.SerializeMap(map6);
                                            if (!zProcessFiles2) {
                                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_FAIL", "value", strSerializeMap2);
                                            } else if (z3) {
                                                APIS.dynamic_analytics_api(TAG, "PROCESSINSTALL_SUCCESS", "value", strSerializeMap2);
                                            }
                                        }
                                    }
                                    z2 = false;
                                }
                            }
                            i6++;
                            i3 = 0;
                        }
                        z2 = z3;
                    }
                } else {
                    z2 = z3;
                }
                com.emotix.arya.app_utils.Log.a(TAG, "processInstall Completed");
            } else {
                APIS.dynamic_analytics_api(TAG, "download directory is not found");
                z2 = false;
            }
            APIS.dynamic_analytics_api(TAG, "completed naming files ");
            if (z2) {
                this.callback.downloadStatus(0, 0, -4);
            } else if (!z2) {
                this.callback.downloadStatus(0, 0, -5);
            }
        } catch (Exception e) {
            com.emotix.arya.app_utils.Log.a(TAG, "Error in install app process");
            this.callback.downloadStatus(0, 0, -5);
            e.printStackTrace();
        }
    }

    public int getProcessPercentage(int i, int i2) {
        if (i == i2) {
            return 100;
        }
        return Math.round((i * 100) / i2);
    }

    private void copyFile(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] bArr = new byte[1024];
        while (true) {
            int i = inputStream.read(bArr);
            if (i == -1) {
                return;
            } else {
                outputStream.write(bArr, 0, i);
            }
        }
    }
}
