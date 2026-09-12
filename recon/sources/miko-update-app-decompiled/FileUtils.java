package com.emotix.arya.app_utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import com.bumptech.glide.load.Key;
import com.emotix.arya.comm.utils.mikoProperties;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

/* JADX INFO: loaded from: classes.dex */
public class FileUtils {
    private static final String TAG = "FileUtils";
    private static FileUtils instance;
    private Context context;
    private Gson gson = new Gson();

    public void writeAnalyticsxx(String str) {
    }

    public static FileUtils getInstance(Context context) {
        if (instance == null) {
            instance = new FileUtils(context);
        }
        return instance;
    }

    private FileUtils(Context context) {
        this.context = context;
    }

    public boolean isExternalStorageWritable() {
        return "mounted".equals(Environment.getExternalStorageState());
    }

    private String getDateTime() {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date());
    }

    public void writeFile(byte[] bArr, String str, boolean z) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(new File(str), z);
            fileOutputStream.write(bArr, 0, bArr.length);
            fileOutputStream.flush();
            fileOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "error in writefile");
        }
    }

    public boolean unzipUsingLibray(String str, String str2) {
        return unzipUsingLibray(str, str2, null, 0, 0);
    }

    public String unzipUsingLibray1(String str, String str2, ZipCallback zipCallback, int i, int i2) {
        int size;
        if (!new File(str).exists()) {
            Log.e("AppStore", "file does not exist");
            return "-1";
        }
        File file = new File(str2);
        while (!file.exists()) {
            file.mkdirs();
        }
        try {
            Log.e("AppStore", "source file: " + str);
            ZipFile zipFile = new ZipFile(str);
            if (!zipFile.isValidZipFile()) {
                Log.e("AppStore", "zip file is invalid");
                return "-2";
            }
            List<FileHeader> fileHeaders = zipFile.getFileHeaders();
            for (int i3 = 0; i3 < fileHeaders.size(); i3++) {
                FileHeader fileHeader = fileHeaders.get(i3);
                String fileName = fileHeader.getFileName();
                if (fileHeader.isDirectory()) {
                    Log.e("AppStore", "directory: " + fileName);
                } else {
                    Log.e("AppStore", "file: " + fileName);
                    zipFile.extractFile(fileHeader, str2);
                }
                if (zipCallback != null && i2 != 0 && (size = (int) ((((double) i3) * 100.0d) / ((double) fileHeaders.size()))) > 0 && size % 20 == 0) {
                    zipCallback.progressCallback(fileHeaders.size(), i3, (((int) ((((double) (i2 - i)) * 1.0d) / ((double) fileHeaders.size()))) * i3) + i);
                }
            }
            Log.e("AppStore", "unzip complete");
            return "0";
        } catch (Exception e) {
            Log.e("AppStore", "error in unzip: " + e.getMessage());
            return AppUtils.getException(e);
        }
    }

    public boolean unzipUsingLibray(String str, String str2, ZipCallback zipCallback, int i, int i2) {
        if (!new File(str).exists()) {
            Log.e("AppStore", "file does not exist");
            return false;
        }
        File file = new File(str2);
        while (!file.exists()) {
            file.mkdirs();
        }
        try {
            ZipFile zipFile = new ZipFile(str);
            if (!zipFile.isValidZipFile()) {
                Log.e(TAG, "zip file is invalid");
                return false;
            }
            List<FileHeader> fileHeaders = zipFile.getFileHeaders();
            for (int i3 = 0; i3 < fileHeaders.size(); i3++) {
                FileHeader fileHeader = fileHeaders.get(i3);
                android.util.Log.i("AppStore", "unziping FileName " + fileHeader.getFileName());
                zipFile.extractFile(fileHeader, str2);
                if (zipCallback != null && i2 != 0) {
                    zipCallback.progressCallback(fileHeaders.size(), i3, (((i2 - i) * i3) / fileHeaders.size()) + i);
                }
            }
            Log.e("zzzz", "Completed unzip");
            return true;
        } catch (Exception e) {
            Log.e("AppStore", "error in unzip");
            e.printStackTrace();
            return false;
        }
    }

    public String getExpressionString(String str) {
        File file = new File(new File(getInternalStorage(null), "expressions"), str);
        return file.exists() ? readJSON(file.getAbsolutePath()) : "";
    }

    public File getInternalStorage(Context context) {
        return new File(Environment.getExternalStorageDirectory() + "/klug/APPS/");
    }

    public void recursiveDelete(File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                for (File file2 : file.listFiles()) {
                    recursiveDelete(file2);
                }
            }
            file.getAbsoluteFile().delete();
        }
    }

    public File getRootInternalStorage(Context context) {
        return new File(Environment.getExternalStorageDirectory(), "klug");
    }

    public boolean checkProperties(Context context) {
        if (new File(getInstance(context).getRootInternalStorage(context), "miko.properties").exists()) {
            return true;
        }
        Log.e(TAG, "APP is not installed ,critical error ");
        return false;
    }

    public File getDownloadAppDir(Context context) {
        File file = new File(new File(Environment.getExternalStorageDirectory(), "klug"), "downloads");
        if (!file.exists()) {
            file.mkdirs();
        }
        File file2 = new File(file, "ENC");
        if (!file2.exists()) {
            file2.mkdirs();
        }
        return file2;
    }

    public File getDownloadCache() {
        File file = new File(new File(Environment.getExternalStorageDirectory(), "klug"), "cache");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public File getDownloadDir(Context context) {
        File file = new File(new File(Environment.getExternalStorageDirectory(), "klug"), "downloads");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public String checkHashMd5(File file) {
        String str;
        StringBuilder sb;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            try {
                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] bArr = new byte[8192];
                while (true) {
                    try {
                        try {
                            int i = fileInputStream.read(bArr);
                            if (i > 0) {
                                messageDigest.update(bArr, 0, i);
                            } else {
                                String strReplace = String.format("%32s", new BigInteger(1, messageDigest.digest()).toString(16)).replace(' ', '0');
                                try {
                                    fileInputStream.close();
                                    return strReplace;
                                } catch (IOException e) {
                                    e = e;
                                    str = TAG;
                                    sb = new StringBuilder();
                                }
                            }
                        } catch (IOException e2) {
                            Log.e(TAG, "unable to process file for md5 " + e2.getMessage());
                            try {
                                fileInputStream.close();
                                return null;
                            } catch (IOException e3) {
                                e = e3;
                                str = TAG;
                                sb = new StringBuilder();
                            }
                        }
                    } catch (Throwable th) {
                        try {
                            fileInputStream.close();
                            throw th;
                        } catch (IOException e4) {
                            e = e4;
                            str = TAG;
                            sb = new StringBuilder();
                        }
                    }
                    sb.append("Exception on closing MD5 input stream");
                    sb.append(e.getMessage());
                    Log.e(str, sb.toString());
                    return null;
                }
            } catch (FileNotFoundException e5) {
                Log.e(TAG, "Exception while getting FileInputStream" + e5.getMessage());
                return null;
            }
        } catch (NoSuchAlgorithmException e6) {
            Log.e(TAG, "Exception while getting digest" + e6.getMessage());
            return null;
        }
    }

    public File getAltInstallDir(Context context) {
        return new File(getRootInternalStorage(context), "INSTALL");
    }

    public File getRecoveryDir(Context context) {
        return new File(getRootInternalStorage(context), "RECOVERY");
    }

    public File getInstallDir(Context context) {
        File file = new File(getRootInternalStorage(context), "TMP");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public File getExternalStorage(Context context) {
        String str = mikoProperties.getInstance(context).getpropVal("EXTERNAL_DIR");
        if (str == null || str.length() <= 0) {
            str = "/storage/sdcard1/APPS/";
        }
        return new File(str);
    }

    public File getUpdateAppInstallDirectory(Context context) {
        String str = mikoProperties.getInstance(context).getpropVal("UPDATE_APP_DIR");
        if (str == null || str.length() <= 0) {
            str = "/storage/sdcard1/UPDATE_APP_INSTALL_DIR/";
        }
        return new File(str);
    }

    public boolean isExternalStorageReadable() {
        String externalStorageState = Environment.getExternalStorageState();
        return "mounted".equals(externalStorageState) || "mounted_ro".equals(externalStorageState);
    }

    public void copyDirectoryOneLocationToAnotherLocation(File file, File file2) throws IOException {
        FileOutputStream fileOutputStream;
        if (!file.exists()) {
            Log.e(TAG, "source in unreadable " + file.getAbsolutePath());
            return;
        }
        if (file.isDirectory()) {
            Log.e(TAG, "source is directory " + file.getAbsolutePath());
            if (!file2.exists()) {
                Log.e(TAG, "target location " + file2.getAbsolutePath());
                file2.mkdir();
            }
            String[] list = file.list();
            for (int i = 0; i < file.listFiles().length; i++) {
                copyDirectoryOneLocationToAnotherLocation(new File(file, list[i]), new File(file2, list[i]));
            }
            return;
        }
        Log.e(TAG, "source is file " + file.getAbsolutePath() + ":" + file2.getAbsolutePath());
        FileInputStream fileInputStream = new FileInputStream(file);
        if (file2.isDirectory()) {
            fileOutputStream = new FileOutputStream(new File(file2, file.getName()));
        } else {
            fileOutputStream = new FileOutputStream(file2);
        }
        byte[] bArr = new byte[1024];
        while (true) {
            int i2 = fileInputStream.read(bArr);
            if (i2 > 0) {
                fileOutputStream.write(bArr, 0, i2);
            } else {
                fileInputStream.close();
                fileOutputStream.flush();
                fileOutputStream.close();
                return;
            }
        }
    }

    public static boolean deleteContents(File file) {
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles == null) {
            return true;
        }
        boolean zDeleteContents = true;
        for (File file2 : fileArrListFiles) {
            if (file2.isDirectory()) {
                zDeleteContents &= deleteContents(file2);
            }
            if (!file2.delete()) {
                Log.e(TAG, "Failed to delete " + file2.getAbsolutePath());
                zDeleteContents = false;
            }
        }
        return zDeleteContents;
    }

    public File getFile(String str) throws IOException {
        if (!isExternalStorageReadable()) {
            throw new IOException("CAn read media storage, if device >= 23 check runtime permission");
        }
        File file = new File(Environment.getExternalStorageDirectory(), str);
        if (!file.mkdirs()) {
            Log.e(TAG, "getFile: Doesn't created " + str);
        }
        return file;
    }

    public String getExtension(String str) {
        return MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(str)).toString());
    }

    public String getBaseName(String str) {
        Log.e(TAG, "file path " + str + ":" + str.lastIndexOf("."));
        if (str.lastIndexOf(".") > 0) {
            return str.substring(0, str.lastIndexOf("."));
        }
        return null;
    }

    public String readJSON(String str) {
        try {
            byte[] file = readFile(str);
            if (file != null) {
                return new String(file, Key.STRING_CHARSET_NAME);
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean isAssetExists(Context context, String str) {
        AssetManager assets = context.getResources().getAssets();
        InputStream inputStream = null;
        try {
            try {
                try {
                    InputStream inputStreamOpen = assets.open(str);
                    if (inputStreamOpen != null) {
                        try {
                            inputStreamOpen.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return true;
                    }
                    inputStreamOpen.close();
                    return false;
                } catch (Throwable th) {
                    try {
                        inputStream.close();
                    } catch (IOException e2) {
                        e2.printStackTrace();
                    }
                    throw th;
                }
            } catch (IOException e3) {
                e3.printStackTrace();
                inputStream.close();
            }
        } catch (IOException e4) {
            e4.printStackTrace();
            return false;
        }
    }

    public byte[] readStream(InputStream inputStream) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            inputStream.available();
            byte[] bArr = new byte[1024];
            int i = 1024;
            while (true) {
                int iAvailable = inputStream.available();
                if (iAvailable > 0) {
                    i = iAvailable < i ? iAvailable : 1024;
                    byteArrayOutputStream.write(bArr, 0, inputStream.read(bArr, 0, i));
                } else {
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    byteArrayOutputStream.close();
                    inputStream.close();
                    return byteArray;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] readFile(String str) {
        try {
            return readStream(new FileInputStream(str));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] readAssets(Context context, String str) {
        try {
            if (isAssetExists(context, str)) {
                return readStream(context.getResources().getAssets().open(str));
            }
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            Log.w("EL", "error ");
            return null;
        }
    }

    public String getDataFromFile(File file) throws Throwable {
        StringBuilder sb = new StringBuilder();
        InputStreamReader inputStreamReader = null;
        try {
            try {
                try {
                    InputStreamReader inputStreamReader2 = new InputStreamReader(new FileInputStream(file));
                    try {
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader2);
                        while (true) {
                            String line = bufferedReader.readLine();
                            if (line == null) {
                                break;
                            }
                            sb.append(line);
                        }
                        if (inputStreamReader2 != null) {
                            inputStreamReader2.close();
                        }
                    } catch (Exception e) {
                        e = e;
                        inputStreamReader = inputStreamReader2;
                        e.printStackTrace();
                        if (inputStreamReader != null) {
                            inputStreamReader.close();
                        }
                        return sb.toString();
                    } catch (Throwable th) {
                        th = th;
                        inputStreamReader = inputStreamReader2;
                        if (inputStreamReader != null) {
                            try {
                                inputStreamReader.close();
                            } catch (IOException e2) {
                                e2.printStackTrace();
                            }
                        }
                        throw th;
                    }
                } catch (IOException e3) {
                    e3.printStackTrace();
                }
            } catch (Exception e4) {
                e = e4;
            }
            return sb.toString();
        } catch (Throwable th2) {
            th = th2;
        }
    }

    public PathModel getPathInfo() throws Throwable {
        try {
            return (PathModel) new Gson().fromJson(getDataFromFile(getFile("klug/path_info.json")), PathModel.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getExpressionPath(String str) {
        return new File(new File(getInternalStorage(null), "expressions"), str).getAbsolutePath();
    }

    public static int readBrightness() throws Throwable {
        try {
            File file = new File("/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness");
            StringBuilder sb = new StringBuilder();
            InputStreamReader inputStreamReader = null;
            try {
                try {
                    InputStreamReader inputStreamReader2 = new InputStreamReader(new FileInputStream(file));
                    try {
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader2);
                        while (true) {
                            String line = bufferedReader.readLine();
                            if (line == null) {
                                break;
                            }
                            sb.append(line);
                        }
                        if (inputStreamReader2 != null) {
                            try {
                                inputStreamReader2.close();
                            } catch (IOException e) {
                                e = e;
                                e.printStackTrace();
                            }
                        }
                    } catch (Exception e2) {
                        e = e2;
                        inputStreamReader = inputStreamReader2;
                        e.printStackTrace();
                        Log.e("FileUtils", "Exception = " + e.getMessage());
                        if (inputStreamReader != null) {
                            try {
                                inputStreamReader.close();
                            } catch (IOException e3) {
                                e = e3;
                                e.printStackTrace();
                            }
                        }
                    } catch (Throwable th) {
                        th = th;
                        inputStreamReader = inputStreamReader2;
                        if (inputStreamReader != null) {
                            try {
                                inputStreamReader.close();
                            } catch (IOException e4) {
                                e4.printStackTrace();
                            }
                        }
                        throw th;
                    }
                } catch (Exception e5) {
                    e = e5;
                }
                Log.e("FileUtils", "sb = " + sb.toString());
                return Integer.parseInt(sb.toString());
            } catch (Throwable th2) {
                th = th2;
            }
        } catch (Exception e6) {
            Log.e("FileUtils", "Exception = " + e6.getMessage());
            return -1;
        }
    }

    public static void writeBrightness(int i) {
        AppUtils.runCommands("echo " + i + " > /sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness");
    }
}
