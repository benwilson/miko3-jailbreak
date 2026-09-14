package com.miko3.launcher;

import android.app.Application;
import android.content.Intent;

/**
 * Starts the info web server and the drive-lease coordinator once per
 * process, independent of Activity lifecycle — the coordinator (R3/KTD3)
 * must survive exactly the events (a mode's crash or exit) it arbitrates.
 */
public class LauncherApp extends Application {

    private InfoHttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        server = new InfoHttpServer(this);
        Thread t = new Thread(server, "info-http-server");
        t.setDaemon(true);
        t.start();

        startService(new Intent(this, DriveLeaseService.class));
    }
}
