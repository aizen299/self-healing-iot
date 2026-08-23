package io.fleet.edge;

import java.util.Locale;

/** Where telemetry goes. */
public enum SinkType {

    /**
     * Counts and discards. The default, and what Pillar A measurements use:
     * it isolates the cost of producing telemetry from the cost of moving it,
     * so the constrained-vs-naive comparison is not measuring the MQTT client.
     * It also means tests and quick runs need no broker.
     */
    COUNTING,

    /** Publishes to a real broker, one connection per device. */
    MQTT;

    public static SinkType parse(String raw) {
        return SinkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
