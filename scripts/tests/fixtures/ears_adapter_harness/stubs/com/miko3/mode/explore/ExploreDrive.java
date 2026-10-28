package com.miko3.mode.explore;

/**
 * Host-JVM stand-in for ExploreDrive, which needs the Android SDK: only the
 * ReadingListener interface EarsAdapter implements.
 */
final class ExploreDrive {
    interface ReadingListener {
        void onReading(SensorReading reading);
    }

    private ExploreDrive() {
    }
}
