package com.miko3.shared;

import android.content.Context;

/**
 * Host-JVM stand-in for the ears client: no Binder, no worker. The harness
 * calls EarsAdapter.onHeard itself, as the real client's worker would. The
 * Listener signature is the real one, so a change there breaks this compile.
 */
public class RobotEarsClient {
    public interface Listener {
        void onHeard(String text, int side, float angle, int tier, long at, boolean partial, int kind,
                     boolean called, String message);

        void onAnswering(long at);

        void onAnswerOver(long at);

        void onProvisional(long at, String text);

        default void onVoice(long at, String person, float score, int band, float margin) {
        }

        void onLost(String reason);
    }

    public int listens;

    public RobotEarsClient(Context context) {
    }

    public void setCharger(boolean latched) {
    }

    public void open(boolean chargerLatched, Listener listener) {
    }

    public void listen(long maxMs) {
        listens++;
    }

    public void clipWindow(long durationMs) {
    }

    public void shoved(long atElapsedMs) {
    }

    public void close() {
    }
}
