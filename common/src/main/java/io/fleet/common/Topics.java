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

    /** Wildcard subscription for one kind across the whole fleet. */
    public static String allDevices(String kind) {
        return ROOT + "/+/" + kind;
    }

    /**
     * Device id from a fleet topic, or {@code null} when the topic is not one
     * of ours.
     *
     * <p>Returns null rather than throwing because a subscriber receives
     * whatever the broker sends, including topics from other publishers on a
     * shared broker. An unrecognised topic is data to be counted and ignored,
     * not an error condition.
     */
    public static String deviceIdOf(String topic) {
        String[] parts = split(topic);
        return parts == null ? null : parts[1];
    }

    /** Trailing segment of a fleet topic ({@code telemetry}, {@code status}, …), or null. */
    public static String kindOf(String topic) {
        String[] parts = split(topic);
        return parts == null ? null : parts[2];
    }

    private static String[] split(String topic) {
        if (topic == null) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length != 3 || !ROOT.equals(parts[0])
                || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        return parts;
    }

    private Topics() {
    }
}
