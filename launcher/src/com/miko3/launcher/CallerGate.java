package com.miko3.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Android half of the launcher's caller check, shared by every exported
 * service modes call (RobotSettingsService, SpeechService): looks up the
 * calling uid's packages and their signing certificates and hands them to
 * the plain-Java CallerCheck, which makes the decision.
 */
final class CallerGate {
    private CallerGate() {
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package. Must run on the Binder thread, inside the call. The only log
     * line is a denial, naming the caller's uid and packages. */
    static void enforce(Context context, String tag) {
        int uid = Binder.getCallingUid();
        final PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean allowed = CallerCheck.allows(packages, new CallerCheck.Signers() {
            @Override
            public List<String> digestsOf(String packageName) {
                return signingDigests(pm, packageName);
            }
        });
        if (!allowed) {
            Log.w(tag, "denied uid " + uid + " packages " + Arrays.toString(packages));
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
