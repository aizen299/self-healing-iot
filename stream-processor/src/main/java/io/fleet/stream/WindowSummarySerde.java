package io.fleet.stream;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.ByteBuffer;

/**
 * Fixed-layout codec for the running aggregate.
 *
 * <p>Kafka Streams persists aggregates to a changelog topic and restores them
 * after a restart, so this format is durable state rather than a transport
 * detail — a change to it must be treated as a state migration, not a
 * refactor.
 *
 * <p>Six fixed-width fields in a known order rather than JSON: the aggregate
 * is written on every single record, so the cheapest correct encoding is the
 * right one, and there is no interoperability requirement to justify anything
 * more forgiving.
 */
final class WindowSummarySerde implements Serde<FleetTopology.WindowSummary> {

    private static final int BYTES = Long.BYTES * 3 + Double.BYTES * 3;

    @Override
    public Serializer<FleetTopology.WindowSummary> serializer() {
        return (topic, summary) -> {
            if (summary == null) {
                return null;
            }
            return ByteBuffer.allocate(BYTES)
                    .putLong(summary.count())
                    .putDouble(summary.temperatureSum())
                    .putDouble(summary.maxVibration())
                    .putDouble(summary.minBattery())
                    .putLong(summary.degraded())
                    .putLong(summary.critical())
                    .array();
        };
    }

    @Override
    public Deserializer<FleetTopology.WindowSummary> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            if (bytes.length != BYTES) {
                // Loud rather than silently short-reading: a wrong length here
                // means the changelog holds a different version of this
                // record, and guessing would corrupt every window that follows.
                throw new IllegalArgumentException(
                        "window summary must be " + BYTES + " bytes, got " + bytes.length);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new FleetTopology.WindowSummary(
                    buffer.getLong(), buffer.getDouble(), buffer.getDouble(),
                    buffer.getDouble(), buffer.getLong(), buffer.getLong());
        };
    }
}
