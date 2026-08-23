package io.fleet.common;

/**
 * A parsed {@code fleet/{deviceId}/{kind}} topic.
 *
 * <p>Parsed once per message rather than field by field: a subscriber needs
 * both halves for every message it routes, and the gateway's ingest path is
 * one of the things Phase 8 measures.
 *
 * @param deviceId the device the message concerns
 * @param kind     trailing segment: {@code telemetry}, {@code status}, …
 */
public record FleetTopic(String deviceId, String kind) {

    /**
     * Parses a fleet topic, or returns {@code null} when the topic is not one
     * of ours.
     *
     * <p>Null rather than an exception because a subscriber receives whatever
     * the broker sends, including traffic from other publishers on a shared
     * broker. An unrecognised topic is data to count and ignore, not an error.
     */
    public static FleetTopic parse(String topic) {
        if (topic == null) {
            return null;
        }
        int firstSlash = topic.indexOf('/');
        if (firstSlash < 0 || !Topics.ROOT.regionMatches(0, topic, 0, firstSlash)
                || firstSlash != Topics.ROOT.length()) {
            return null;
        }
        int secondSlash = topic.indexOf('/', firstSlash + 1);
        if (secondSlash < 0
                || secondSlash == firstSlash + 1
                || secondSlash == topic.length() - 1
                || topic.indexOf('/', secondSlash + 1) >= 0) {
            return null;
        }
        return new FleetTopic(
                topic.substring(firstSlash + 1, secondSlash),
                topic.substring(secondSlash + 1));
    }
}
