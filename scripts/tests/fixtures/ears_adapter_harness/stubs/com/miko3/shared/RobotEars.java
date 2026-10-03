package com.miko3.shared;

/**
 * Host-JVM stand-in for the launcher's ears Binder: only the wire constants
 * EarsAdapter maps. The real interface extends IInterface and carries the
 * Stub/Proxy; test_ears_adapter checks these values against it.
 */
public interface RobotEars {
    int TIER_NONE = 0;
    int TIER_WEAK = 1;
    int TIER_STRONG = 2;
    int VOICE_NONE = 0;
    int VOICE_WEAK = 1;
    int VOICE_STRONG = 2;
    int SIDE_LEFT = -1;
    int SIDE_NONE = 0;
    int SIDE_RIGHT = 1;
    int KIND_WAKE_WORD = 0;
    int KIND_NAME = 1;
    int KIND_GREETING = 2;
    int KIND_APOLOGY = 3;
    int KIND_VOICE = 4;
    int KIND_MISSING = -1;
}
