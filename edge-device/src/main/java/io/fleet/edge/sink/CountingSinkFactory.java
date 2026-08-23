package io.fleet.edge.sink;

import io.fleet.common.TelemetrySink;
import io.fleet.common.TelemetrySinkFactory;

/**
 * Hands every device the same {@link CountingSink}.
 *
 * <p>Sharing is correct here because a counting sink has no per-device state
 * and no connection to own — the point is a fleet-wide total. This is the
 * factory Pillar A measurements use: it isolates the cost of producing
 * telemetry from the cost of transporting it, so the constrained-vs-naive
 * comparison is not measuring the MQTT client.
 */
public final class CountingSinkFactory implements TelemetrySinkFactory {

    private final CountingSink sink = new CountingSink();

    @Override
    public TelemetrySink create(String deviceId) {
        return sink;
    }

    @Override
    public void abandon(String deviceId) {
        // Nothing to release: the sink is shared and holds no connection.
    }

    @Override
    public long payloadCount() {
        return sink.payloadCount();
    }

    @Override
    public long byteCount() {
        return sink.byteCount();
    }

    @Override
    public void close() {
        sink.close();
    }
}
