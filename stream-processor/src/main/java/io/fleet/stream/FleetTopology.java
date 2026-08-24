package io.fleet.stream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.fleet.common.DeviceStatus;
import io.fleet.common.KafkaTopics;
import io.fleet.common.MalformedPayloadException;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryParser;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/**
 * Windowed aggregation over the fleet's telemetry.
 *
 * <p>Reads {@code telemetry.raw}, groups by device, and emits a summary per
 * device per window to {@code telemetry.processed}: reading count, mean
 * temperature, peak vibration, minimum battery, and how many readings the
 * device itself reported as degraded or critical.
 *
 * <p>Built as a {@link Topology} separate from any runner so it can be driven
 * by {@code TopologyTestDriver} — the aggregation is the part with logic in
 * it, and testing it through a real broker would be slow and flaky for no
 * gain.
 *
 * <p>Deliberately modest. The gateway remains the system of record and the
 * thing that detects failures; this derives fleet-level statistics from the
 * stream, which is what Kafka is here for. Routing detection through it as
 * well would put a broker between a device failing and anybody noticing.
 */
public final class FleetTopology {

    /** Malformed records seen, exposed so a run can tell silence from garbage. */
    private final LongAdder malformed = new LongAdder();

    private final Duration windowSize;
    private final Duration grace;

    public FleetTopology(Duration windowSize, Duration grace) {
        this.windowSize = windowSize;
        this.grace = grace;
    }

    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();
        TelemetryParser parser = new TelemetryParser();
        JsonFactory json = new JsonFactory();

        builder.stream(KafkaTopics.TELEMETRY_RAW,
                        Consumed.with(Serdes.String(), Serdes.ByteArray()))
                // A record that will not parse is dropped and counted, never
                // fatal. One bad producer must not stop the fleet's statistics
                // being computed — and an uncaught exception here would take
                // the whole stream thread down.
                .flatMapValues(this::parseOrDrop)
                .groupByKey(Grouped.with(Serdes.String(), Serdes.ByteArray()))
                .windowedBy(TimeWindows.ofSizeAndGrace(windowSize, grace))
                .aggregate(
                        WindowSummary::empty,
                        (deviceId, raw, summary) -> summary.add(decode(parser, raw)),
                        Materialized.with(Serdes.String(), new WindowSummarySerde()))
                .toStream()
                .map((Windowed<String> window, WindowSummary summary) ->
                        new org.apache.kafka.streams.KeyValue<>(
                                window.key(), encode(json, window, summary)))
                .to(KafkaTopics.TELEMETRY_PROCESSED,
                        Produced.with(Serdes.String(), Serdes.ByteArray()));

        return builder.build();
    }

    /**
     * Returns the record if it parses, or nothing if it does not.
     *
     * <p>{@code flatMapValues} with an empty list is how a Streams topology
     * drops a record without failing the stream; returning null would be a
     * different thing entirely and would reach the aggregator.
     */
    private Iterable<byte[]> parseOrDrop(byte[] raw) {
        try {
            new TelemetryParser().parse(raw, 0, raw.length);
            return java.util.List.of(raw);
        } catch (MalformedPayloadException e) {
            malformed.increment();
            System.err.println("dropped a malformed record from telemetry.raw: "
                    + e.getMessage());
            return java.util.List.of();
        }
    }

    private static Telemetry decode(TelemetryParser parser, byte[] raw) {
        try {
            return parser.parse(raw, 0, raw.length);
        } catch (MalformedPayloadException e) {
            // Unreachable: parseOrDrop already rejected anything unparseable.
            throw new IllegalStateException("a record passed the filter and then failed", e);
        }
    }

    private static byte[] encode(JsonFactory json, Windowed<String> window,
            WindowSummary summary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField("deviceId", window.key());
            generator.writeNumberField("windowStart", window.window().start());
            generator.writeNumberField("windowEnd", window.window().end());
            generator.writeNumberField("readings", summary.count());
            generator.writeNumberField("meanTemperature", summary.meanTemperature());
            generator.writeNumberField("maxVibration", summary.maxVibration());
            generator.writeNumberField("minBattery", summary.minBattery());
            generator.writeNumberField("degradedReadings", summary.degraded());
            generator.writeNumberField("criticalReadings", summary.critical());
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }

    public long malformedCount() {
        return malformed.sum();
    }

    /**
     * Running aggregate for one device in one window.
     *
     * <p>Immutable, because Kafka Streams may retain and re-serialise an
     * aggregate between updates; mutating in place is how an aggregation
     * quietly produces the wrong answer after a restore from the changelog.
     *
     * <p>The mean is carried as a running sum rather than a running average:
     * averaging averages loses the count and gives the wrong result the moment
     * windows are merged.
     */
    record WindowSummary(
            long count,
            double temperatureSum,
            double maxVibration,
            double minBattery,
            long degraded,
            long critical) {

        static WindowSummary empty() {
            return new WindowSummary(0L, 0.0d, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 0L, 0L);
        }

        WindowSummary add(Telemetry reading) {
            return new WindowSummary(
                    count + 1,
                    temperatureSum + reading.temperature(),
                    Math.max(maxVibration, reading.vibration()),
                    Math.min(minBattery, reading.batteryLevel()),
                    degraded + (reading.status() == DeviceStatus.DEGRADED ? 1 : 0),
                    critical + (reading.status() == DeviceStatus.CRITICAL ? 1 : 0));
        }

        double meanTemperature() {
            return count == 0L ? 0.0d : temperatureSum / count;
        }
    }
}
