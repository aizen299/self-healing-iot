package io.fleet.common;

/**
 * Kafka topic names, shared so no module invents its own spelling.
 *
 * <p>The set is deliberately small and fixed. Kafka arrived only after
 * MQTT → gateway already worked end to end, and the point of introducing it
 * is scalable downstream processing — not to become the bus every internal
 * operation travels on. The gateway still keeps its own registry, its own
 * detection, and its own store; what goes to Kafka is what something
 * downstream needs to consume.
 *
 * <p>Messages are keyed by device id on every topic. That is what makes a
 * device's readings and its failures land on the same partition and stay in
 * order relative to each other, which any windowed aggregation depends on.
 */
public final class KafkaTopics {

    /** Every accepted reading, as the gateway received it. */
    public static final String TELEMETRY_RAW = "telemetry.raw";

    /** Windowed aggregates produced by the stream processor. */
    public static final String TELEMETRY_PROCESSED = "telemetry.processed";

    /** Every health transition the gateway announces. */
    public static final String DEVICE_EVENTS = "device.events";

    /**
     * Failures only — a strict subset of {@link #DEVICE_EVENTS}.
     *
     * <p>Separate because Phase 9's recovery controller cares about exactly
     * one kind of event and should not have to filter a firehose to find it.
     * A consumer that only ever acts on failures should not be able to see
     * anything else by accident.
     */
    public static final String DEVICE_FAILURES = "device.failures";

    /** Confirmed recoveries, carrying the duration MTTR is computed from. */
    public static final String DEVICE_RECOVERY = "device.recovery";

    private KafkaTopics() {
    }
}
