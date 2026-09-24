package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.miko3.shared.ClaudeAccess;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotSettings;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Exported bound Service that hands the robot's Claude API settings to our
 * own mode apps (settings plan U5, KTD1; R13, R14, R16). Every call first
 * checks who is calling (CallerCheck) and only then reads the store, so a
 * vendor app gets a SecurityException and never a key, and a Save or Forget
 * key on the Settings page shows up on a mode's next call.
 *
 * Nothing here logs the answer. The only log line is a denial, which names
 * the caller's uid and packages.
 */
public class RobotSettingsService extends Service {
    private static final String TAG = "RobotSettingsService";
    public static final String ACTION_BIND = LauncherProtocol.ROBOT_SETTINGS_ACTION;

    private final RobotSettings.Stub binder = new RobotSettings.Stub() {
        @Override
        public ClaudeAccess getClaudeAccess() {
            enforceCaller();
            ClaudeSettings.Credentials c = ((LauncherApp) getApplication()).claudeSettings().credentialsForRequests();
            return CallerCheck.accessFor(c);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package. Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        final PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean allowed = CallerCheck.allows(packages, new CallerCheck.Signers() {
            @Override
            public List<String> digestsOf(String packageName) {
                return signingDigests(pm, packageName);
            }
        });
        if (!allowed) {
            Log.w(TAG, "denied uid " + uid + " packages " + Arrays.toString(packages));
            throw new SecurityException("not a Miko 3 app");
        }
    }

    /** Lower-case hex SHA-256 of each signing certificate, or null if the
     * package isn't installed. */
    @SuppressWarnings("deprecation")
    private static List<String> signingDigests(PackageManager pm, String packageName) {
        PackageInfo info;
        try {
            info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        List<String> digests = new ArrayList<String>();
        if (info.signatures == null) {
            return digests;
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Android runtime ships SHA-256; with no digest, nothing matches.
            return null;
        }
        for (Signature sig : info.signatures) {
            byte[] hash = sha256.digest(sig.toByteArray());
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            digests.add(hex.toString());
        }
        return digests;
    }
}
