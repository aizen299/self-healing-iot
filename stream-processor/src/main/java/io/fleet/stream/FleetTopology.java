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
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.Suppressed.BufferConfig;
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
                .groupByKey(Grouped.with(Serdes.String(), Serdes.ByteArray()))
                .windowedBy(TimeWindows.ofSizeAndGrace(windowSize, grace))
                // Parsed once, here. The previous shape validated in a
                // flatMapValues and parsed again in the aggregator, so every
                // record on the topology's only hot path was decoded twice —
                // and Phase 8 measures this throughput.
                .aggregate(
                        WindowSummary::empty,
                        (deviceId, raw, summary) -> accumulate(parser, summary, raw),
                        Materialized.with(Serdes.String(), new WindowSummarySerde()))
                // One record per window instead of one per input record.
                // Without this the topic carries a changelog of partial
                // aggregates at the input rate, which is neither what the
                // README promises nor of any use to a consumer that wants a
                // summary.
                .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
                .toStream()
                .map((Windowed<String> window, WindowSummary summary) ->
                        new org.apache.kafka.streams.KeyValue<>(
                                window.key(), encode(json, window, summary)))
                .to(KafkaTopics.TELEMETRY_PROCESSED,
                        Produced.with(Serdes.String(), Serdes.ByteArray()));

        return builder.build();
    }

    /**
     * Folds one record into the running aggregate, skipping what will not parse.
     *
     * <p>A malformed record leaves the aggregate untouched and is counted.
     * Throwing here would take the stream thread down with it, so one bad
     * producer would stop the whole fleet's statistics being computed.
     */
    private WindowSummary accumulate(TelemetryParser parser, WindowSummary summary, byte[] raw) {
        try {
            return summary.add(parser.parse(raw, 0, raw.length));
        } catch (MalformedPayloadException e) {
            malformed.increment();
            System.err.println("dropped a malformed record from telemetry.raw: "
                    + e.getMessage());
            return summary;
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
            // Null rather than the sentinels an empty aggregate carries.
            // Infinity is not valid JSON, and a window that saw only malformed
            // records reaches here with count zero.
            if (summary.count() == 0L) {
                generator.writeNullField("maxVibration");
                generator.writeNullField("minBattery");
            } else {
                generator.writeNumberField("maxVibration", summary.maxVibration());
                generator.writeNumberField("minBattery", summary.minBattery());
            }
            generator.writeNumberField("degradedReadings", summary.degraded());
            generator.writeNumberField("criticalReadings", summary.critical());
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
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
