package io.fleet.edge.sink;

import io.fleet.common.TelemetrySink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps every payload as a string, for tests and small demonstrations.
 *
 * <p>Copies on receipt, because {@link TelemetrySink} allows callers to reuse
 * their buffer — the constrained variant does exactly that, and retaining the
 * array would leave every recorded entry pointing at the newest reading.
 *
 * <p>Unbounded, so it is not for long runs or for measurement.
 */
public final class RecordingSink implements TelemetrySink {

    private final List<String> topics = new ArrayList<>();
    private final List<String> payloads = new ArrayList<>();

    @Override
    public synchronized void publish(String topic, byte[] payload, int offset, int length) {
        topics.add(topic);
        payloads.add(new String(payload, offset, length, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    public synchronized List<String> topics() {
        return Collections.unmodifiableList(new ArrayList<>(topics));
    }

    public synchronized List<String> payloads() {
        return Collections.unmodifiableList(new ArrayList<>(payloads));
    }

    public synchronized int size() {
        return payloads.size();
    }
}
