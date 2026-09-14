package com.miko3.launcher;

import android.app.Application;

/** Starts the info web server once per process, independent of Activity lifecycle. */
public class LauncherApp extends Application {

    private InfoHttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        server = new InfoHttpServer(this);
        Thread t = new Thread(server, "info-http-server");
        t.setDaemon(true);
        t.start();
    }
}
