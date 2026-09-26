package com.miko3.launcher;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.NoSuchElementException;

/**
 * A LeaseKeeper.Token over a client's IBinder: linkToDeath registers a
 * DeathRecipient that runs onDeath, and unlinkToDeath drops it, swallowing
 * the NoSuchElementException of a link that is already gone (binderDied may
 * have fired concurrently). Shared by DriveLeaseService.acquire and
 * ListenEngine.openEars.
 */
final class BinderToken implements LeaseKeeper.Token {
    private final IBinder binder;
    private IBinder.DeathRecipient recipient;

    BinderToken(IBinder binder) {
        this.binder = binder;
    }

    @Override
    public void linkToDeath(final Runnable onDeath) throws RemoteException {
        IBinder.DeathRecipient r = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                onDeath.run();
            }
        };
        binder.linkToDeath(r, 0);
        recipient = r;
    }

    @Override
    public void unlinkToDeath(Runnable onDeath) {
        if (recipient == null) {
            return;
        }
        try {
            binder.unlinkToDeath(recipient, 0);
        } catch (NoSuchElementException ignored) {
            // Already unlinked.
        }
        recipient = null;
    }
}
