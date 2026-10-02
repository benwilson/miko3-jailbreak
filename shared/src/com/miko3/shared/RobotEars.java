package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this build)
 * for the launcher's continuous ears session (meeting plan U3; R1-R6, R21;
 * KTD1, KTD2, KTD3, KTD6). Same shape as RobotListen. Modes reach it through
 * RobotEarsClient rather than binding directly.
 *
 * open() takes the one session for the calling uid and streams every heard
 * utterance to the Callback, one-way, as {text, side, angle, tier, at,
 * partial, kind}; the launcher throws IllegalStateException ("ears held by
 * another app") when another uid holds it. renew() is the heartbeat, once a
 * second, carrying the caller's charger latch (KTD6): false means the session
 * is no longer this uid's (missed renews, a death, a close). listen() marks a
 * conversation listen; clipWindow() opens the deaf window for a local clip;
 * shoved() stamps a shove or collision stop for the classifier. A listen's
 * answer that has started is announced first, one-way, by Callback.answering
 * (robot 2026-10-01), so the mode holds the listen for its words, and one
 * that ends without words by Callback.answerOver (review 2026-10-01), so the
 * hold ends with it. The launcher
 * checks the caller on every call (SecurityException to any app that isn't
 * ours) and binds renew, close, listen, clipWindow and shoved to the uid that
 * opened the session.
 *
 * Every Proxy method checks the transaction result, so an older launcher
 * that lacks this Binder is detected (RemoteException) rather than ignored.
 */
public interface RobotEars extends IInterface {
    /** The renewal period the launcher expects (three missed renews release the session). */
    long RENEW_PERIOD_MS = 1000;

    int TIER_NONE = 0;
    int TIER_WEAK = 1;
    int TIER_STRONG = 2;
    int SIDE_LEFT = -1;
    int SIDE_NONE = 0;
    int SIDE_RIGHT = 1;
    /** The cue kind (Callback.heard's last field): the wake-word engine fired, or the phrase was heard. */
    int KIND_WAKE_WORD = 0;
    /** His name in any form the recogniser writes it. */
    int KIND_NAME = 1;
    /** A clear greeting aimed at him, or any other strong utterance. */
    int KIND_GREETING = 2;
    /** An apology word or phrase; strong within the shove window, weak otherwise. */
    int KIND_APOLOGY = 3;
    /** Any other weak utterance: a voice burst, a half-heard word. */
    int KIND_VOICE = 4;
    /** Never sent: what Callback.Stub reports when an older launcher's parcel ends before the kind. */
    int KIND_MISSING = -1;

    void open(Callback callback, boolean chargerLatched) throws RemoteException;

    boolean renew(boolean chargerLatched) throws RemoteException;

    void close() throws RemoteException;

    void listen(long maxMs) throws RemoteException;

    void clipWindow(long durationMs) throws RemoteException;

    void shoved(long atElapsedMs) throws RemoteException;

    /** One heard utterance. Called one-way from the launcher, on a Binder thread of the calling app. */
    interface Callback extends IInterface {
        /**
         * text is the recogniser's, trimmed, possibly empty for a voice burst;
         * side is SIDE_*; angle the latched median direction in degrees, NaN
         * when none; tier is TIER_*; at the utterance's start
         * (SystemClock.elapsedRealtime); partial when the deaf window clipped it;
         * kind is KIND_*, the launcher's own naming of the cue (its classifier
         * sees the wake-word engine and the shove clock), so the mode never
         * guesses it from the text. kind is appended to the KTD1 shape
         * {text, side, angle, tier, at, partial} (owner-approved 2026-09-26): an
         * older mode ignores the trailing int, and a newer mode under an older
         * launcher receives KIND_MISSING. called (Hey Miko plan KTD4) is
         * appended after it: true on the end-of-utterance delivery of a wake
         * word the launcher already sent as an early cue for the same at, so
         * the mode makes no second call. An older launcher's parcel ends before
         * it, which reads as false. message (owner 2026-10-02) is appended after
         * called: a call's words besides the address ("how's it going" from "Hey
         * Miko, how's it going?"), the caller's first message, "" for anything
         * else. An older mode ignores the trailing string; an older launcher's
         * parcel ends before it, which reads as null: a bare call.
         */
        void heard(String text, int side, float angle, int tier, long at, boolean partial, int kind,
                   boolean called, String message) throws RemoteException;

        /**
         * Robot 2026-10-01: the open conversation listen's answer has started (the
         * launcher claimed an utterance that began inside the start window, or at
         * most 500 ms before the listen opened); at is when its speech began. Sent
         * once per listen, one-way, before the answer's heard(). Appended as its
         * own code (2), never folded into heard's parcel: an older mode's Stub has
         * no case for it and Binder.onTransact returns false, which a one-way
         * sender never sees, so that mode behaves as before; a newer mode under an
         * older launcher simply never receives it, and its listens end at maxMs.
         * The mode holds a listen it was sent for LauncherProtocol.EARS_ANSWER_HOLD_MS.
         */
        void answering(long at) throws RemoteException;

        /**
         * Review 2026-10-01 (P2-2): the listen that said answering(at) ended without
         * delivering words (a cough, a door, speech the recogniser heard as ""), so the
         * mode stops holding it and ends it as silence. At most once per listen, after
         * its answering(), one-way. Appended as its own code (3), handled exactly like
         * code 2: an older mode's Stub has no case for it (onTransact returns false,
         * which a one-way sender never sees) and holds the listen as before; a newer
         * mode under an older launcher never receives it and holds as before.
         */
        void answerOver(long at) throws RemoteException;

        /**
         * Robot 2026-10-02: the open conversation listen's answer so far, its segments
         * joined, sent each time the recogniser endpoints inside it with new words (about
         * 0.8 s after the last word) while the 2 s silence rule still runs; at is when its
         * speech began. The answer itself still comes by heard(); this lets the mode start
         * its turn early and use it only if the words are the same. Appended as its own
         * code (4), one-way, handled exactly like codes 2 and 3: an older mode's Stub has
         * no case for it (onTransact returns false, which a one-way sender never sees);
         * a newer mode under an older launcher never receives it and asks as before.
         */
        void provisional(long at, String text) throws RemoteException;

        abstract class Stub extends Binder implements Callback {
            private static final String DESCRIPTOR = "com.miko3.shared.RobotEars.Callback";
            static final int TRANSACTION_heard = 1;
            static final int TRANSACTION_answering = 2;
            static final int TRANSACTION_answerOver = 3;
            static final int TRANSACTION_provisional = 4;

            public Stub() {
                attachInterface(this, DESCRIPTOR);
            }

            @Override
            public IBinder asBinder() {
                return this;
            }

            public static Callback asInterface(IBinder binder) {
                if (binder == null) {
                    return null;
                }
                IInterface local = binder.queryLocalInterface(DESCRIPTOR);
                if (local instanceof Callback) {
                    return (Callback) local;
                }
                return new Proxy(binder);
            }

            @Override
            public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                switch (code) {
                    case TRANSACTION_heard: {
                        data.enforceInterface(DESCRIPTOR);
                        String text = data.readString();
                        int side = data.readInt();
                        float angle = data.readFloat();
                        int tier = data.readInt();
                        long at = data.readLong();
                        boolean partial = data.readInt() != 0;
                        int kind = data.dataAvail() > 0 ? data.readInt() : KIND_MISSING;
                        boolean called = data.dataAvail() > 0 && data.readInt() != 0;
                        String message = data.dataAvail() > 0 ? data.readString() : null;
                        heard(text, side, angle, tier, at, partial, kind, called, message);
                        return true;
                    }
                    case TRANSACTION_answering: {
                        data.enforceInterface(DESCRIPTOR);
                        answering(data.readLong());
                        return true;
                    }
                    case TRANSACTION_answerOver: {
                        data.enforceInterface(DESCRIPTOR);
                        answerOver(data.readLong());
                        return true;
                    }
                    case TRANSACTION_provisional: {
                        data.enforceInterface(DESCRIPTOR);
                        long at = data.readLong();
                        provisional(at, data.readString());
                        return true;
                    }
                    case IBinder.INTERFACE_TRANSACTION:
                        reply.writeString(DESCRIPTOR);
                        return true;
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            }

            private static class Proxy implements Callback {
                private final IBinder remote;

                Proxy(IBinder remote) {
                    this.remote = remote;
                }

                @Override
                public IBinder asBinder() {
                    return remote;
                }

                /** One-way, so the launcher's capture thread never waits on a mode. */
                @Override
                public void heard(String text, int side, float angle, int tier, long at, boolean partial,
                                  int kind, boolean called, String message) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        data.writeString(text);
                        data.writeInt(side);
                        data.writeFloat(angle);
                        data.writeInt(tier);
                        data.writeLong(at);
                        data.writeInt(partial ? 1 : 0);
                        data.writeInt(kind);
                        data.writeInt(called ? 1 : 0);
                        data.writeString(message);
                        remote.transact(TRANSACTION_heard, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }

                /** One-way, like heard(): the capture thread never waits on a mode. */
                @Override
                public void answering(long at) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        data.writeLong(at);
                        remote.transact(TRANSACTION_answering, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }

                /** One-way, like answering(). */
                @Override
                public void answerOver(long at) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        data.writeLong(at);
                        remote.transact(TRANSACTION_answerOver, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }

                /** One-way, like answering(). */
                @Override
                public void provisional(long at, String text) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        data.writeLong(at);
                        data.writeString(text);
                        remote.transact(TRANSACTION_provisional, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }
            }
        }
    }

    abstract class Stub extends Binder implements RobotEars {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotEars";
        // Appended in declaration order; never renumbered (both APKs install together).
        static final int TRANSACTION_open = 1;
        static final int TRANSACTION_renew = 2;
        static final int TRANSACTION_close = 3;
        static final int TRANSACTION_listen = 4;
        static final int TRANSACTION_clipWindow = 5;
        static final int TRANSACTION_shoved = 6;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotEars asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotEars) {
                return (RobotEars) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_open: {
                    data.enforceInterface(DESCRIPTOR);
                    Callback callback = Callback.Stub.asInterface(data.readStrongBinder());
                    boolean charger = data.readInt() != 0;
                    open(callback, charger);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_renew: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean ok = renew(data.readInt() != 0);
                    reply.writeNoException();
                    reply.writeInt(ok ? 1 : 0);
                    return true;
                }
                case TRANSACTION_close: {
                    data.enforceInterface(DESCRIPTOR);
                    close();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_listen: {
                    data.enforceInterface(DESCRIPTOR);
                    listen(data.readLong());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_clipWindow: {
                    data.enforceInterface(DESCRIPTOR);
                    clipWindow(data.readLong());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_shoved: {
                    data.enforceInterface(DESCRIPTOR);
                    shoved(data.readLong());
                    reply.writeNoException();
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

        private static class Proxy implements RobotEars {
            private static final String TOO_OLD = "the launcher has no ears session (install both APKs together)";
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void open(Callback callback, boolean chargerLatched) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    data.writeInt(chargerLatched ? 1 : 0);
                    if (!remote.transact(TRANSACTION_open, data, reply, 0)) {
                        throw new RemoteException(TOO_OLD);
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean renew(boolean chargerLatched) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(chargerLatched ? 1 : 0);
                    if (!remote.transact(TRANSACTION_renew, data, reply, 0)) {
                        throw new RemoteException(TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void close() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (!remote.transact(TRANSACTION_close, data, reply, 0)) {
                        throw new RemoteException(TOO_OLD);
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void listen(long maxMs) throws RemoteException {
                sendLong(TRANSACTION_listen, maxMs);
            }

            @Override
            public void clipWindow(long durationMs) throws RemoteException {
                sendLong(TRANSACTION_clipWindow, durationMs);
            }

            @Override
            public void shoved(long atElapsedMs) throws RemoteException {
                sendLong(TRANSACTION_shoved, atElapsedMs);
            }

            private void sendLong(int code, long value) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(value);
                    if (!remote.transact(code, data, reply, 0)) {
                        throw new RemoteException(TOO_OLD);
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
