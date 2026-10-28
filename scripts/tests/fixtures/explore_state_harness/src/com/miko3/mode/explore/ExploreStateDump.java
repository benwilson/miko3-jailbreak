package com.miko3.mode.explore;

/**
 * Host-JVM dump for scripts/tests/test_explore_state_page.py (explore plan U6):
 * prints the explore eyes page, then one "STATE <json>" line per brain-facing
 * state. The Python side makes the assertions; this only exposes the
 * package-private strings.
 */
public final class ExploreStateDump {
    public static void main(String[] args) {
        System.out.println("PAGE-BEGIN");
        System.out.println(ExploreState.DEVICE_VIEW_HTML);
        System.out.println("PAGE-END");
        for (String name : ExploreState.ALL_STATES) {
            System.out.println("STATE " + ExploreState.of(name).toJson());
        }
        System.out.println("STATE " + ExploreState.look(ExploreState.LOOK, -1.5, 0.25).toJson());
        // The holder's /state body with a few gauges counted and stamped (meeting plan U7, KTD14).
        ExploreState.Holder holder = new ExploreState.Holder();
        holder.set(ExploreState.of(ExploreState.LISTENING));
        holder.count("cues");
        holder.count("cues");
        holder.count("leanIns");
        holder.count("not-a-counter");
        holder.stamp("cueAt", 1234);
        holder.stamp("firstSound", 5678);
        holder.stamp("callHeard", 2468);
        holder.stamp("not-a-stage", 1);
        System.out.println("HOLDER " + holder.json());
    }
}
