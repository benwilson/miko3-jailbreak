package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this build)
 * for the launcher's people store (explore-on-claude plan U2; R13, R14, KTD1,
 * KTD3). Same shape as RobotSettings and RobotSpeech. Modes reach it through
 * RobotPeopleClient rather than binding directly.
 *
 * recent(n) answers the n most recently seen people (at most MAX_RECENT),
 * newest first, each as an id and a 224 px face JPEG, for Explore's face
 * match request. add() remembers a new person, seen now, and answers their
 * id. touch() marks someone seen now (false for an unknown id). nameOf()
 * answers a name, "" for an unnamed person, or null once the owner has
 * forgotten them. Names never leave the launcher except through nameOf(), so
 * the match request itself carries faces only (KTD3). notesOf(), mergeNotes()
 * and forget() (meeting plan U5, KTD10) carry a person's notes as PersonNotes
 * JSON and wipe a person by id, never by name.
 *
 * Face plan U4 (R1, R5, R8, R9; KTD4, KTD10-KTD12) appends transactions 8 to
 * 14 for on-robot matching: gallery() answers every named person's stored
 * photos as (id, slot, added-at, model id, embedding), no names and no
 * images; photo() answers one JPEG by id and slot for the mode to embed;
 * idsNamed() answers the ids a spoken name could mean; addPhoto() and
 * addPerson() store a photo with the embedding the mode computed;
 * setEmbedding() and markUnusable() write back the mode's migration result
 * and are refused (false) once the slot's photo has been replaced or the
 * person forgotten.
 *
 * Face plan U5 (R13, R14, R19; KTD8) appends 15 and 16: recordCheck() hands
 * the launcher one face check (crop, best match by id, score, decision; see
 * FaceCheck) for the Settings page's memory-only ring and answers a handle;
 * updateCheck() sets that check's outcome, and closes a still-pending one as
 * ended without an answer when the meeting ends.
 *
 * The launcher checks the caller on every call and throws SecurityException
 * to any app that isn't one of ours, and IllegalArgumentException with a
 * fixed reason for a face it won't store; Binder carries both back to the
 * Proxy, where reply.readException() rethrows them.
 */
public interface RobotPeople extends IInterface {
    /** The most faces one recent() answer carries (KTD3). With the store's
     * per-face cap, ten stay far below Binder's 1 MB transaction buffer. */
    int MAX_RECENT = 10;

    /** The most photos one gallery() answer carries: 50 people with 5 photos
     * each. With the store's embedding cap each entry is about 1 KB at most
     * (0.5 KB for SFace), so the reply stays far below Binder's 1 MB buffer.
     * The launcher answers the most recently seen people first. */
    int MAX_GALLERY = 250;

    /** The most ids one idsNamed() answer carries. */
    int MAX_IDS_NAMED = 100;

    Face[] recent(int n) throws RemoteException;

    /** Remembers a new person: a face JPEG and their name, which is required
     * (R19: nobody is stored without one); answers their id. The launcher
     * throws IllegalArgumentException with the store's fixed reason for a
     * missing or blank name, as for a face it won't store. */
    String add(byte[] faceJpeg, String name) throws RemoteException;

    boolean touch(String id) throws RemoteException;

    String nameOf(String id) throws RemoteException;

    /** The person's notes as PersonNotes JSON (meeting plan U5, KTD10); the
     * empty document for an unknown id. Appended: its Proxy throws
     * UnsupportedOperationException (LauncherProtocol.LAUNCHER_TOO_OLD) when
     * the launcher predates it, as do mergeNotes() and forget(). */
    String notesOf(String id) throws RemoteException;

    /** Merges a delta (a PersonNotes JSON object) into the person's notes and
     * answers the merged document. IllegalArgumentException with the store's
     * or PersonNotes' fixed reason for an unknown id or a bad delta. */
    String mergeNotes(String id, String deltaJson) throws RemoteException;

    /** Wipes the person's face, name and notes (R18); false if unknown. */
    boolean forget(String id) throws RemoteException;

    // Face plan U4 (KTD10-KTD12). Appended: each Proxy throws
    // UnsupportedOperationException (LauncherProtocol.LAUNCHER_TOO_OLD) when
    // the launcher predates it.

    /** Every stored photo of every named person, most recently seen first
     * (at most MAX_GALLERY), pending and unusable ones included (R18). */
    GalleryPhoto[] gallery() throws RemoteException;

    /** One stored photo JPEG, or null for an unknown id or an empty slot. */
    byte[] photo(String id, int slot) throws RemoteException;

    /** The ids of named people a spoken name could mean (KTD10): two or more
     * words match the full name, one word the first name, ignoring case. */
    String[] idsNamed(String name) throws RemoteException;

    /** Adds a photo and its embedding to a remembered person, filling an
     * empty slot or replacing the oldest (R5); answers the slot.
     * IllegalArgumentException with the store's fixed reason for an unknown
     * id (KTD12: a forgotten person is never brought back), a bad photo or
     * a bad embedding. */
    int addPhoto(String id, byte[] faceJpeg, String modelId, float[] embedding) throws RemoteException;

    /** Remembers a new person with their first photo and its embedding in
     * one step (KTD12); answers their id. Refusals as add() and addPhoto(). */
    String addPerson(byte[] faceJpeg, String name, String modelId, float[] embedding) throws RemoteException;

    /** Stores the embedding for a photo the mode fetched (KTD11); false when
     * the person is gone or the slot no longer holds the photo added at
     * addedAtMillis. */
    boolean setEmbedding(String id, int slot, long addedAtMillis, String modelId, float[] embedding)
            throws RemoteException;

    /** Marks a stored photo in which the mode found no face (KTD11); false
     * as setEmbedding(). */
    boolean markUnusable(String id, int slot, long addedAtMillis) throws RemoteException;

    // Face plan U5 (KTD8). Appended as above.

    /** Records one face check in the launcher's ring of recent checks and
     * answers its handle (above 0). IllegalArgumentException with a fixed
     * reason for a crop over FaceCheck.MAX_CROP_BYTES, a crop that isn't a
     * JPEG, or an unknown code. */
    long recordCheck(FaceCheck check) throws RemoteException;

    /** Sets a recorded check's outcome (a FaceCheck outcome code) and, for an
     * answer that joined the crop to someone, their id (else null).
     * FaceCheck.ENDED_WITHOUT_ANSWER closes the check only if it is still
     * pending. False, changing nothing, once the check has rolled off the
     * ring or been purged by forget. */
    boolean updateCheck(long handle, int outcome, String joinedId) throws RemoteException;

    /** One stored photo as gallery() answers it: never a name or an image. */
    final class GalleryPhoto {
        public final String id;
        public final int slot;
        /** 0 for a photo stored before added-at times were recorded. */
        public final long addedAtMillis;
        /** The mode found no face in it: never a match exemplar, never pending. */
        public final boolean unusable;
        /** "" when there is no embedding. */
        public final String modelId;
        /** null when there is no embedding. */
        public final float[] embedding;

        public GalleryPhoto(String id, int slot, long addedAtMillis, boolean unusable, String modelId,
                            float[] embedding) {
            this.id = id;
            this.slot = slot;
            this.addedAtMillis = addedAtMillis;
            this.unusable = unusable;
            this.modelId = modelId == null ? "" : modelId;
            this.embedding = embedding;
        }

        /** True while this photo still waits for an embedding from
         * currentModelId (R18): no embedding, or one from another model. */
        public boolean isPending(String currentModelId) {
            return !unusable && (embedding == null || !modelId.equals(currentModelId));
        }
    }

    /** One remembered person's id and face, as recent() answers them. */
    final class Face {
        public final String id;
        public final byte[] jpeg;

        public Face(String id, byte[] jpeg) {
            this.id = id;
            this.jpeg = jpeg;
        }
    }

    abstract class Stub extends Binder implements RobotPeople {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotPeople";
        static final int TRANSACTION_recent = 1;
        static final int TRANSACTION_add = 2;
        static final int TRANSACTION_touch = 3;
        static final int TRANSACTION_nameOf = 4;
        static final int TRANSACTION_notesOf = 5;
        static final int TRANSACTION_mergeNotes = 6;
        static final int TRANSACTION_forget = 7;
        static final int TRANSACTION_gallery = 8;
        static final int TRANSACTION_photo = 9;
        static final int TRANSACTION_idsNamed = 10;
        static final int TRANSACTION_addPhoto = 11;
        static final int TRANSACTION_addPerson = 12;
        static final int TRANSACTION_setEmbedding = 13;
        static final int TRANSACTION_markUnusable = 14;
        static final int TRANSACTION_recordCheck = 15;
        static final int TRANSACTION_updateCheck = 16;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotPeople asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotPeople) {
                return (RobotPeople) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_recent: {
                    data.enforceInterface(DESCRIPTOR);
                    Face[] faces = recent(data.readInt());
                    reply.writeNoException();
                    int count = faces == null ? 0 : faces.length;
                    reply.writeInt(count);
                    for (int i = 0; i < count; i++) {
                        reply.writeString(faces[i].id);
                        reply.writeByteArray(faces[i].jpeg);
                    }
                    return true;
                }
                case TRANSACTION_add: {
                    data.enforceInterface(DESCRIPTOR);
                    byte[] jpeg = data.createByteArray();
                    String name = data.readString();
                    String id = add(jpeg, name);
                    reply.writeNoException();
                    reply.writeString(id);
                    return true;
                }
                case TRANSACTION_touch: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean known = touch(data.readString());
                    reply.writeNoException();
                    reply.writeInt(known ? 1 : 0);
                    return true;
                }
                case TRANSACTION_nameOf: {
                    data.enforceInterface(DESCRIPTOR);
                    String name = nameOf(data.readString());
                    reply.writeNoException();
                    reply.writeString(name);
                    return true;
                }
                case TRANSACTION_notesOf: {
                    data.enforceInterface(DESCRIPTOR);
                    String notes = notesOf(data.readString());
                    reply.writeNoException();
                    reply.writeString(notes);
                    return true;
                }
                case TRANSACTION_mergeNotes: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    String delta = data.readString();
                    String merged = mergeNotes(id, delta);
                    reply.writeNoException();
                    reply.writeString(merged);
                    return true;
                }
                case TRANSACTION_forget: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean known = forget(data.readString());
                    reply.writeNoException();
                    reply.writeInt(known ? 1 : 0);
                    return true;
                }
                case TRANSACTION_gallery: {
                    data.enforceInterface(DESCRIPTOR);
                    GalleryPhoto[] photos = gallery();
                    reply.writeNoException();
                    int count = photos == null ? 0 : Math.min(photos.length, MAX_GALLERY);
                    reply.writeInt(count);
                    for (int i = 0; i < count; i++) {
                        reply.writeString(photos[i].id);
                        reply.writeInt(photos[i].slot);
                        reply.writeLong(photos[i].addedAtMillis);
                        reply.writeInt(photos[i].unusable ? 1 : 0);
                        reply.writeString(photos[i].modelId);
                        reply.writeFloatArray(photos[i].embedding);
                    }
                    return true;
                }
                case TRANSACTION_photo: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    byte[] jpeg = photo(id, data.readInt());
                    reply.writeNoException();
                    reply.writeByteArray(jpeg);
                    return true;
                }
                case TRANSACTION_idsNamed: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] ids = idsNamed(data.readString());
                    reply.writeNoException();
                    int count = ids == null ? 0 : Math.min(ids.length, MAX_IDS_NAMED);
                    reply.writeInt(count);
                    for (int i = 0; i < count; i++) {
                        reply.writeString(ids[i]);
                    }
                    return true;
                }
                case TRANSACTION_addPhoto: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    byte[] jpeg = data.createByteArray();
                    String modelId = data.readString();
                    float[] embedding = data.createFloatArray();
                    int slot = addPhoto(id, jpeg, modelId, embedding);
                    reply.writeNoException();
                    reply.writeInt(slot);
                    return true;
                }
                case TRANSACTION_addPerson: {
                    data.enforceInterface(DESCRIPTOR);
                    byte[] jpeg = data.createByteArray();
                    String name = data.readString();
                    String modelId = data.readString();
                    float[] embedding = data.createFloatArray();
                    String id = addPerson(jpeg, name, modelId, embedding);
                    reply.writeNoException();
                    reply.writeString(id);
                    return true;
                }
                case TRANSACTION_setEmbedding: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    int slot = data.readInt();
                    long addedAt = data.readLong();
                    String modelId = data.readString();
                    float[] embedding = data.createFloatArray();
                    boolean stored = setEmbedding(id, slot, addedAt, modelId, embedding);
                    reply.writeNoException();
                    reply.writeInt(stored ? 1 : 0);
                    return true;
                }
                case TRANSACTION_markUnusable: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    int slot = data.readInt();
                    boolean marked = markUnusable(id, slot, data.readLong());
                    reply.writeNoException();
                    reply.writeInt(marked ? 1 : 0);
                    return true;
                }
                case TRANSACTION_recordCheck: {
                    data.enforceInterface(DESCRIPTOR);
                    int decision = data.readInt();
                    int reason = data.readInt();
                    byte[] crop = data.createByteArray();
                    String bestId = data.readString();
                    int bestSlot = data.readInt();
                    long bestAddedAt = data.readLong();
                    float score = data.readFloat();
                    String runnerUpId = data.readString();
                    float runnerUpScore = data.readFloat();
                    boolean nearTie = data.readInt() != 0;
                    long handle = recordCheck(new FaceCheck(decision, reason, crop, bestId, bestSlot, bestAddedAt,
                            score, runnerUpId, runnerUpScore, nearTie));
                    reply.writeNoException();
                    reply.writeLong(handle);
                    return true;
                }
                case TRANSACTION_updateCheck: {
                    data.enforceInterface(DESCRIPTOR);
                    long handle = data.readLong();
                    int outcome = data.readInt();
                    boolean updated = updateCheck(handle, outcome, data.readString());
                    reply.writeNoException();
                    reply.writeInt(updated ? 1 : 0);
                    return true;
                }
                case IBinder.INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements RobotPeople {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public Face[] recent(int n) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(n);
                    remote.transact(TRANSACTION_recent, data, reply, 0);
                    reply.readException();
                    int count = reply.readInt();
                    if (count < 0 || count > MAX_RECENT) {
                        throw new RemoteException("bad people reply");
                    }
                    Face[] faces = new Face[count];
                    for (int i = 0; i < count; i++) {
                        String id = reply.readString();
                        faces[i] = new Face(id, reply.createByteArray());
                    }
                    return faces;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String add(byte[] faceJpeg, String name) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeByteArray(faceJpeg);
                    data.writeString(name);
                    remote.transact(TRANSACTION_add, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean touch(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    remote.transact(TRANSACTION_touch, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String nameOf(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    remote.transact(TRANSACTION_nameOf, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            // The three appended calls check the transaction result: a launcher
            // without the code answers false (Binder's default onTransact) and
            // an empty reply, which must be named, not read as empty notes.

            @Override
            public String notesOf(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    if (!remote.transact(TRANSACTION_notesOf, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String mergeNotes(String id, String deltaJson) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeString(deltaJson);
                    if (!remote.transact(TRANSACTION_mergeNotes, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean forget(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    if (!remote.transact(TRANSACTION_forget, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            // Face plan U4: appended, so each checks the transaction result
            // the same way.

            @Override
            public GalleryPhoto[] gallery() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (!remote.transact(TRANSACTION_gallery, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    int count = reply.readInt();
                    if (count < 0 || count > MAX_GALLERY) {
                        throw new RemoteException("bad people reply");
                    }
                    GalleryPhoto[] photos = new GalleryPhoto[count];
                    for (int i = 0; i < count; i++) {
                        String id = reply.readString();
                        int slot = reply.readInt();
                        long addedAt = reply.readLong();
                        boolean unusable = reply.readInt() != 0;
                        String modelId = reply.readString();
                        photos[i] = new GalleryPhoto(id, slot, addedAt, unusable, modelId, reply.createFloatArray());
                    }
                    return photos;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public byte[] photo(String id, int slot) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeInt(slot);
                    if (!remote.transact(TRANSACTION_photo, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.createByteArray();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String[] idsNamed(String name) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(name);
                    if (!remote.transact(TRANSACTION_idsNamed, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    int count = reply.readInt();
                    if (count < 0 || count > MAX_IDS_NAMED) {
                        throw new RemoteException("bad people reply");
                    }
                    String[] ids = new String[count];
                    for (int i = 0; i < count; i++) {
                        ids[i] = reply.readString();
                    }
                    return ids;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int addPhoto(String id, byte[] faceJpeg, String modelId, float[] embedding) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeByteArray(faceJpeg);
                    data.writeString(modelId);
                    data.writeFloatArray(embedding);
                    if (!remote.transact(TRANSACTION_addPhoto, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String addPerson(byte[] faceJpeg, String name, String modelId, float[] embedding)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeByteArray(faceJpeg);
                    data.writeString(name);
                    data.writeString(modelId);
                    data.writeFloatArray(embedding);
                    if (!remote.transact(TRANSACTION_addPerson, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean setEmbedding(String id, int slot, long addedAtMillis, String modelId, float[] embedding)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeInt(slot);
                    data.writeLong(addedAtMillis);
                    data.writeString(modelId);
                    data.writeFloatArray(embedding);
                    if (!remote.transact(TRANSACTION_setEmbedding, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean markUnusable(String id, int slot, long addedAtMillis) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeInt(slot);
                    data.writeLong(addedAtMillis);
                    if (!remote.transact(TRANSACTION_markUnusable, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public long recordCheck(FaceCheck check) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(check.decision);
                    data.writeInt(check.rejectReason);
                    data.writeByteArray(check.cropJpeg);
                    data.writeString(check.bestId);
                    data.writeInt(check.bestSlot);
                    data.writeLong(check.bestAddedAtMillis);
                    data.writeFloat(check.score);
                    data.writeString(check.runnerUpId);
                    data.writeFloat(check.runnerUpScore);
                    data.writeInt(check.nearTie ? 1 : 0);
                    if (!remote.transact(TRANSACTION_recordCheck, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readLong();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean updateCheck(long handle, int outcome, String joinedId) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(handle);
                    data.writeInt(outcome);
                    data.writeString(joinedId);
                    if (!remote.transact(TRANSACTION_updateCheck, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
