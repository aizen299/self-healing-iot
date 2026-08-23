package io.fleet.edge.sink;

import io.fleet.common.TelemetrySink;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counts payloads and bytes and discards them.
 *
 * <p>The measurement sink for Pillar A: it isolates the cost of *producing*
 * telemetry from the cost of transporting it, so Phase 1 numbers are free of
 * broker and network noise. Phase 2 adds an MQTT sink behind the same
 * interface.
 *
 * <p>{@link LongAdder} rather than {@code AtomicLong} deliberately. The naive
 * variant runs one thread per device, and fifty threads contending on a
 * single CAS would make the counter itself a bottleneck — the instrument
 * would then be measuring its own contention and reporting it as a
 * difference between the variants.
 */
public final class CountingSink implements TelemetrySink {

    private final LongAdder payloads = new LongAdder();
    private final LongAdder bytes = new LongAdder();

    @Override
    public void publish(String topic, byte[] payload, int offset, int length) {
        payloads.increment();
        bytes.add(length);
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    public long payloadCount() {
        return payloads.sum();
    }

    public long byteCount() {
        return bytes.sum();
    }
}
