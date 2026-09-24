package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.miko3.shared.ClaudeAccess;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotSettings;

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
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }
}
