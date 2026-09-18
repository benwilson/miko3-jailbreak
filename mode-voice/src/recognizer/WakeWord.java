package recognizer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Re-declaration of ServiceExam's own recognizer.WakeWord
 * (tools/serviceexam_jadx/sources/recognizer/WakeWord.java), driving the vendor's
 * wake-word engine in libnative_wakeword_vad_lib.so from our own APK (KTD7).
 *
 * JNI binds native methods to the exact fully-qualified class name
 * ("Java_recognizer_WakeWord_xxx" -- confirmed via llvm-nm -D on the bundled .so),
 * so this class MUST stay at recognizer.WakeWord and the native signatures below
 * are copied verbatim from the vendor (scripts/tests/test_build_mode_voice.py
 * checks both). Same pattern as shared/.../emotix/com/drivers/SensorModule.java.
 *
 * Differences from the vendor's Java half:
 *  - the engine's cache directory is a subdirectory of the app's files dir, not
 *    /sdcard/model_serialization_dir/, so no storage permission is needed;
 *  - the asset copy writes to a temp file and renames, so an interrupted first
 *    run cannot leave a truncated model that every later run would load;
 *  - the "Hello Miko" name is a literal (the vendor read it from its Constants).
 */
public class WakeWord {
    /** 80 ms at 16 kHz mono -- the chunk size the engine expects per processChunk(). */
    public static final int AUDIO_CHUNK_SIZE = 1280;
    public static final int DETECTION_HELLO_MIKO = 2;
    public static final int DETECTION_HEY_MIKO = 1;
    public static final int DETECTION_NONE = 0;
    private static final String CACHE_SUBDIR = "wakeword_cache";
    private static final String TAG = "WakeWord";

    /** The library the static initializer loads (kept literal there, as the vendor has it), for callers' failure logs. */
    public static final String LIBRARY = "native_wakeword_vad_lib";

    public static String classToName(int i) {
        if (i != 1) {
            return i != 2 ? "none" : "Hello Miko";
        }
        return "Hey Miko";
    }

    public native float getLastScore();

    public native int getNumOutputClasses();

    public native boolean init(String str, String str2);

    public native int processChunk(short[] sArr, float[] fArr);

    public native void resetState();

    public native void setThreshold(float f);

    public native void setThresholds(float f, float f2);

    static {
        // Bundled as this app's own lib/arm64-v8a/ copy (scripts/build-mode-voice.py);
        // it links libncnn.so and dlopens libtensorflowlite_gpu_delegate.so, both
        // bundled alongside it. A failure here surfaces to the caller as
        // UnsatisfiedLinkError / ExceptionInInitializerError on first use.
        System.loadLibrary("native_wakeword_vad_lib");
    }

    /** Init from an absolute model path; the cache dir lives under the app's files dir. */
    public boolean initFromPath(Context context, String str) {
        File cache = cacheDir(context);
        boolean zInit = init(str, cache.getAbsolutePath());
        if (zInit) {
            Log.i(TAG, "Wakeword engine ready: " + str);
        } else {
            Log.e(TAG, "init() failed for model: " + str);
        }
        return zInit;
    }

    /** Copies the named asset to the app's files dir on first run, then inits from it. */
    public boolean init(Context context, String str) {
        String path;
        try {
            path = copyAssetToInternalStorage(context, str);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy model asset: " + e.getMessage());
            return false;
        }
        return initFromPath(context, path);
    }

    private static File cacheDir(Context context) {
        File file = new File(context.getFilesDir(), CACHE_SUBDIR);
        if (!file.exists() && !file.mkdirs()) {
            Log.w(TAG, "could not create engine cache dir " + file);
        }
        return file;
    }

    private static String copyAssetToInternalStorage(Context context, String str) throws IOException {
        File file = new File(context.getFilesDir(), str);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        Log.i(TAG, "Copying asset to internal storage: " + str);
        File tmp = new File(context.getFilesDir(), str + ".tmp");
        InputStream in = context.getAssets().open(str);
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("rename " + tmp + " -> " + file + " failed");
        }
        Log.i(TAG, "Asset copied: " + file.getAbsolutePath() + "  (" + file.length() + " bytes)");
        return file.getAbsolutePath();
    }
}
