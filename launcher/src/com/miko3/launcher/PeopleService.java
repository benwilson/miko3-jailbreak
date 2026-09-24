package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotPeople;

import java.util.List;

/**
 * Exported bound Service through which our mode apps use the robot's people
 * store (explore-on-claude plan U2; R13, R14, KTD1, KTD3). Every call first
 * checks who is calling (CallerGate, the same pinned-certificate check as
 * RobotSettingsService and SpeechService) and only then touches the store,
 * so a vendor app gets a SecurityException and never a face or a name.
 *
 * The store is the launcher's one PeopleStore (LauncherApp.people()), which
 * the Settings page's People section also renders, so a Rename or Forget
 * there shows up on a mode's next call. Nothing here logs a face or a name;
 * the only log line is CallerGate's denial.
 */
public class PeopleService extends Service {
    private static final String TAG = "PeopleService";
    public static final String ACTION_BIND = LauncherProtocol.ROBOT_PEOPLE_ACTION;

    private final RobotPeople.Stub binder = new RobotPeople.Stub() {
        @Override
        public RobotPeople.Face[] recent(int n) {
            enforceCaller();
            PeopleStore store = people();
            List<PeopleStore.Person> recent = store.recent(Math.min(n, RobotPeople.MAX_RECENT));
            RobotPeople.Face[] faces = new RobotPeople.Face[recent.size()];
            int count = 0;
            for (PeopleStore.Person p : recent) {
                byte[] jpeg = store.face(p.id);
                if (jpeg != null) {
                    faces[count++] = new RobotPeople.Face(p.id, jpeg);
                }
            }
            if (count < faces.length) {
                // A face forgotten between the two reads; answer the rest.
                RobotPeople.Face[] some = new RobotPeople.Face[count];
                System.arraycopy(faces, 0, some, 0, count);
                return some;
            }
            return faces;
        }

        @Override
        public String add(byte[] faceJpeg, String nameOrNull) {
            enforceCaller();
            return people().add(faceJpeg, nameOrNull);
        }

        @Override
        public boolean touch(String id) {
            enforceCaller();
            return people().touch(id);
        }

        @Override
        public String nameOf(String id) {
            enforceCaller();
            return people().nameOf(id);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private PeopleStore people() {
        return ((LauncherApp) getApplication()).people();
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }
}
