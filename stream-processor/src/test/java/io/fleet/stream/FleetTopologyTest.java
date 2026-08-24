package io.fleet.stream;

import io.fleet.common.KafkaTopics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggregation, driven directly.
 *
 * <p>{@code TopologyTestDriver} runs the real topology with real serdes and a
 * controllable clock, so window boundaries and late arrivals can be tested
 * exactly rather than waited for. Exercising this through a live broker would
 * be slower, flakier, and would test Kafka rather than the aggregation.
 */
class FleetTopologyTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_787_500_000_000L);
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private FleetTopology topology;
    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> input;
    private TestOutputTopic<String, byte[]> output;

    @BeforeEach
    void setUp() {
        topology = new FleetTopology(WINDOW, Duration.ZERO);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(topology.build(), props, T0);

        input = driver.createInputTopic(KafkaTopics.TELEMETRY_RAW,
                Serdes.String().serializer(), Serdes.ByteArray().serializer());
        output = driver.createOutputTopic(KafkaTopics.TELEMETRY_PROCESSED,
                Serdes.String().deserializer(), Serdes.ByteArray().deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    @DisplayName("readings in one window are aggregated per device")
    void aggregatesAWindowPerDevice() {
        publish("device-001", T0, 20.0d, 1.0d, 90.0d, "OK");
        publish("device-001", T0.plusMillis(1_000), 30.0d, 3.0d, 80.0d, "OK");
        publish("device-002", T0.plusMillis(2_000), 50.0d, 0.5d, 70.0d, "OK");

        // Advancing past the window closes it and emits the summaries.
        publish("device-001", T0.plus(WINDOW).plusSeconds(1), 99.0d, 9.0d, 9.0d, "OK");

        List<String> summaries = output.readValuesToList().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toList();

        String first = summaries.stream()
                .filter(s -> s.contains("\"deviceId\":\"device-001\"") && s.contains("\"readings\":2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 2-reading window for device-001: "
                        + summaries));

        // Mean of 20 and 30, carried as a running sum rather than an average
        // of averages, which would lose the count.
        assertTrue(first.contains("\"meanTemperature\":25.0"), first);
        assertTrue(first.contains("\"maxVibration\":3.0"), first);
        assertTrue(first.contains("\"minBattery\":80.0"), first);
    }

    @Test
    @DisplayName("device status counts ride along with the aggregate")
    void countsDegradedAndCriticalReadings() {
        publish("device-001", T0, 20.0d, 1.0d, 90.0d, "OK");
        publish("device-001", T0.plusMillis(500), 20.0d, 4.0d, 20.0d, "DEGRADED");
        publish("device-001", T0.plusMillis(900), 20.0d, 4.8d, 5.0d, "CRITICAL");
        publish("device-001", T0.plus(WINDOW).plusSeconds(1), 20.0d, 1.0d, 90.0d, "OK");

        String summary = output.readValuesToList().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .filter(s -> s.contains("\"readings\":3"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 3-reading window emitted"));

        assertTrue(summary.contains("\"degradedReadings\":1"), summary);
        assertTrue(summary.contains("\"criticalReadings\":1"), summary);
    }

    @Test
    @DisplayName("a malformed record is dropped and counted, not fatal")
    void malformedRecordsDoNotKillTheStream() {
        input.pipeInput("device-001", "{not json".getBytes(StandardCharsets.UTF_8), T0);

        // One bad producer must not stop the fleet's statistics being
        // computed, and an uncaught exception here would take the stream
        // thread down with it.
        assertEquals(1L, topology.malformedCount());

        publish("device-001", T0.plusMillis(100), 20.0d, 1.0d, 90.0d, "OK");
        publish("device-001", T0.plus(WINDOW).plusSeconds(1), 20.0d, 1.0d, 90.0d, "OK");

        assertTrue(output.readValuesToList().stream()
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .anyMatch(s -> s.contains("\"readings\":1")),
                "the good record after the bad one must still be aggregated");
    }

    @Test
    @DisplayName("a window emits once, not once per input record")
    void windowsAreSuppressedUntilTheyClose() {
        // Without suppression the aggregate is forwarded on every update, so
        // telemetry.processed carries a changelog of partial windows at the
        // input rate — as many records as came in, each superseding the last.
        for (int i = 0; i < 6; i++) {
            publish("device-001", T0.plusMillis(i * 100L), 20.0d, 1.0d, 90.0d, "OK");
        }
        assertTrue(output.isEmpty(), "nothing should be emitted while the window is open");

        // Advancing past the window closes it.
        publish("device-001", T0.plus(WINDOW).plusSeconds(1), 20.0d, 1.0d, 90.0d, "OK");

        List<String> emitted = output.readValuesToList().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toList();

        assertEquals(1, emitted.size(),
                "one closed window should produce exactly one record: " + emitted);
        assertTrue(emitted.get(0).contains("\"readings\":6"), emitted.get(0));
    }

    @Test
    @DisplayName("devices are aggregated independently")
    void devicesDoNotContaminateEachOther() {
        publish("device-001", T0, 10.0d, 1.0d, 90.0d, "OK");
        publish("device-002", T0, 90.0d, 1.0d, 90.0d, "OK");
        publish("device-001", T0.plus(WINDOW).plusSeconds(1), 0.0d, 0.0d, 0.0d, "OK");
        publish("device-002", T0.plus(WINDOW).plusSeconds(1), 0.0d, 0.0d, 0.0d, "OK");

        List<String> summaries = output.readValuesToList().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toList();

        assertTrue(summaries.stream().anyMatch(
                        s -> s.contains("\"deviceId\":\"device-001\"")
                                && s.contains("\"meanTemperature\":10.0")),
                "device-001's window should average only its own readings: " + summaries);
        assertTrue(summaries.stream().anyMatch(
                        s -> s.contains("\"deviceId\":\"device-002\"")
                                && s.contains("\"meanTemperature\":90.0")),
                "device-002's window should average only its own readings: " + summaries);
    }

    @Test
    @DisplayName("the aggregate survives a serde round trip")
    void aggregateSerdeRoundTrips() {
        // Streams persists this to a changelog and restores it after a
        // restart, so the codec is durable state rather than a detail.
        WindowSummarySerde serde = new WindowSummarySerde();
        FleetTopology.WindowSummary original =
                new FleetTopology.WindowSummary(7L, 140.0d, 4.5d, 12.5d, 2L, 1L);

        byte[] bytes = serde.serializer().serialize("t", original);
        assertEquals(original, serde.deserializer().deserialize("t", bytes));

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> serde.deserializer().deserialize("t", new byte[7]))
                .getMessage().contains("bytes"),
                "a wrong-length record means a different version; guessing would corrupt it");
    }

    private void publish(String deviceId, Instant at, double temp, double vib,
            double batt, String status) {
        String payload = String.format(Locale.ROOT,
                "{\"deviceId\":\"%s\",\"ts\":%d,\"temp\":%.2f,\"vib\":%.2f,\"batt\":%.2f,"
                        + "\"lat\":52.5200,\"lon\":13.4050,\"status\":\"%s\"}",
                deviceId, at.toEpochMilli(), temp, vib, batt, status);
        input.pipeInput(deviceId, payload.getBytes(StandardCharsets.UTF_8), at);
    }
}
