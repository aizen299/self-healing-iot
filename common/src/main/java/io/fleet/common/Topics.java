package io.fleet.common;

/**
 * MQTT topic names.
 *
 * <p>These methods allocate a String per call. That is intentional and is
 * left visible rather than optimised away here: the naive edge-device
 * variant calls them once per reading, while the constrained variant calls
 * them once per device at construction and caches the result. Hiding the
 * cost behind an internal cache would erase a real difference between the
 * two implementations that Pillar A is meant to observe.
 */
public final class Topics {

    public static final String ROOT = "fleet";

    public static String telemetry(String deviceId) {
        return ROOT + "/" + deviceId + "/telemetry";
    }

    public static String heartbeat(String deviceId) {
        return ROOT + "/" + deviceId + "/heartbeat";
    }

    public static String status(String deviceId) {
        return ROOT + "/" + deviceId + "/status";
    }

    public static String events(String deviceId) {
        return ROOT + "/" + deviceId + "/events";
    }

    private Topics() {
    }
}
