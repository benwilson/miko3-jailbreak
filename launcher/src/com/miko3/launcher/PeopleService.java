package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.miko3.shared.FaceCheck;
import com.miko3.shared.Feedback;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotPeople;

import java.util.Arrays;
import java.util.List;

/**
 * Exported bound Service through which our mode apps use the robot's people
 * store (explore-on-claude plan U2; R13, R14, KTD1, KTD3; meeting plan U5,
 * KTD10 for notes and forget; face plan U4, KTD10-KTD12 for photos and
 * embeddings by id; face plan U5, KTD8 for the face-check ring). Every call first
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
                return Arrays.copyOf(faces, count);
            }
            return faces;
        }

        @Override
        public String add(byte[] faceJpeg, String name) {
            enforceCaller();
            return people().add(faceJpeg, name);
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

        @Override
        public String notesOf(String id) {
            enforceCaller();
            return people().notes(id).toJson();
        }

        @Override
        public String mergeNotes(String id, String deltaJson) {
            enforceCaller();
            return people().mergeNotes(id, deltaJson).toJson();
        }

        @Override
        public boolean forget(String id) {
            enforceCaller();
            return FaceChecks.forget(people(), checks(), id);
        }

        @Override
        public RobotPeople.GalleryPhoto[] gallery() {
            enforceCaller();
            List<PeopleStore.Photo> photos = people().gallery();
            // Most recently seen people come first, so a cap keeps the regulars.
            int count = Math.min(photos.size(), RobotPeople.MAX_GALLERY);
            RobotPeople.GalleryPhoto[] out = new RobotPeople.GalleryPhoto[count];
            for (int i = 0; i < count; i++) {
                PeopleStore.Photo p = photos.get(i);
                out[i] = new RobotPeople.GalleryPhoto(p.id, p.slot, p.addedAtMillis, p.unusable, p.modelId,
                        p.embedding);
            }
            return out;
        }

        @Override
        public byte[] photo(String id, int slot) {
            enforceCaller();
            return people().photo(id, slot);
        }

        @Override
        public String[] idsNamed(String name) {
            enforceCaller();
            List<String> ids = people().idsNamed(name);
            return ids.subList(0, Math.min(ids.size(), RobotPeople.MAX_IDS_NAMED)).toArray(new String[0]);
        }

        @Override
        public int addPhoto(String id, byte[] faceJpeg, String modelId, float[] embedding) {
            enforceCaller();
            return people().addPhoto(id, faceJpeg, modelId, embedding);
        }

        @Override
        public String addPerson(byte[] faceJpeg, String name, String modelId, float[] embedding) {
            enforceCaller();
            return people().addPerson(faceJpeg, name, modelId, embedding);
        }

        @Override
        public boolean setEmbedding(String id, int slot, long addedAtMillis, String modelId, float[] embedding) {
            enforceCaller();
            return people().setEmbedding(id, slot, addedAtMillis, modelId, embedding);
        }

        @Override
        public boolean markUnusable(String id, int slot, long addedAtMillis) {
            enforceCaller();
            return people().markUnusable(id, slot, addedAtMillis);
        }

        @Override
        public long recordCheck(FaceCheck check) {
            enforceCaller();
            return checks().record(check);
        }

        @Override
        public boolean updateCheck(long handle, int outcome, String joinedId) {
            enforceCaller();
            if (outcome == FaceCheck.ENDED_WITHOUT_ANSWER) {
                return checks().closeAsEnded(handle);
            }
            return checks().updateOutcome(handle, outcome, joinedId);
        }

        @Override
        public boolean recordFeedback(String id, String kind, String summary, String quote, String context) {
            enforceCaller();
            // Re-checked here with the same rules Explore applied: a bad entry is refused, never stored.
            Feedback f = Feedback.of(kind, summary, quote);
            return f != null && people().recordFeedback(id, f, context);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private PeopleStore people() {
        return ((LauncherApp) getApplication()).people();
    }

    private FaceChecks checks() {
        return ((LauncherApp) getApplication()).faceChecks();
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }
}
