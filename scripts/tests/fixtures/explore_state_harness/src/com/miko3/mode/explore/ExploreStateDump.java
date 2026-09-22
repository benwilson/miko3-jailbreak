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
    }
}
