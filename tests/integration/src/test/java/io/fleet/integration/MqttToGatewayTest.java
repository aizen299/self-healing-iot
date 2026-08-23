package io.fleet.integration;

import io.fleet.common.Presence;
import io.fleet.common.SensorModel;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.FailureInjector;
import io.fleet.edge.FailureMode;
import io.fleet.edge.constrained.ConstrainedEdgeDevice;
import io.fleet.edge.harness.FleetHarness;
import io.fleet.edge.mqtt.MqttConfig;
import io.fleet.edge.mqtt.MqttSinkFactory;
import io.fleet.edge.naive.NaiveEdgeDevice;
import io.fleet.edge.sink.RecordingSink;
import io.fleet.gateway.DeviceRecord;
import io.fleet.gateway.DeviceRegistry;
import io.fleet.gateway.GatewayConfig;
import io.fleet.gateway.GatewayMetrics;
import io.fleet.gateway.MqttIngestor;
import io.fleet.gateway.TelemetryParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device → MQTT → Gateway, end to end over a real broker.
 *
 * <p>Lives here rather than in either module because it is the boundary
 * between them that is under test. Skipped when no broker is listening.
 */
class MqttToGatewayTest {

    private static final String BROKER =
            System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://127.0.0.1:1883");

    @BeforeEach
    void requireBroker() {
        Assumptions.assumeTrue(brokerReachable(),
                "no MQTT broker at " + BROKER + " — skipping integration test");
    }

    @Test
    @DisplayName("telemetry published by devices reaches the gateway and updates the registry")
    void telemetryFlowsFromDevicesToTheGateway() throws Exception {
        String prefix = "itg" + System.nanoTime();
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();

        GatewayConfig gatewayConfig = GatewayConfig.from(Map.of(
                "MQTT_BROKER_URL", BROKER,
                "GATEWAY_CLIENT_ID", "gw-" + System.nanoTime(),
                "GATEWAY_HTTP_PORT", "0"));

        try (MqttIngestor ingestor = new MqttIngestor(gatewayConfig, registry, metrics)) {
            ingestor.start();

            DeviceConfig deviceConfig = DeviceConfig.from(Map.of(
                    "FLEET_SINK", "mqtt",
                    "FLEET_DEVICE_COUNT", "3",
                    "FLEET_DEVICE_ID_PREFIX", prefix,
                    "FLEET_PUBLISH_INTERVAL_MS", "100"));

            try (MqttSinkFactory sinks = new MqttSinkFactory(MqttConfig.from(
                         Map.of("MQTT_BROKER_URL", BROKER,
                                "MQTT_CLIENT_ID_PREFIX", "itg" + System.nanoTime())));
                 FleetHarness harness = new FleetHarness(deviceConfig, sinks)) {

                harness.start();
                awaitUntil(() -> metrics.acceptedCount() >= 3L);

                // Asserted while the fleet is still up: presence rides the
                // device's own connection, so once it closes the correct answer
                // becomes OFFLINE.
                awaitUntil(() -> presenceOf(registry, prefix + "-001") == Presence.ONLINE);

                Optional<DeviceRecord> first = registry.find(prefix + "-001");
                assertTrue(first.isPresent(), "gateway should know device " + prefix + "-001");
                assertTrue(first.get().telemetryAccepted() >= 1L);
                assertEquals(0L, first.get().telemetryRejected());
            }

            assertTrue(metrics.acceptedCount() >= 3L,
                    "gateway should have accepted a reading from each device");
            assertEquals(0L, metrics.malformedCount(), "well-formed telemetry must not be rejected");
            assertEquals(0L, metrics.invalidCount(), "in-range telemetry must not be rejected");

            // Closing the fleet is an orderly shutdown, so each device publishes
            // retained OFFLINE and the gateway follows the whole lifecycle.
            awaitUntil(() -> presenceOf(registry, prefix + "-001") == Presence.OFFLINE);
            assertEquals(0L, registry.onlineCount(),
                    "no device should still be reported online after a clean shutdown");
        }
    }

    @Test
    @DisplayName("the gateway counts a malformed payload without dropping the device")
    void malformedPayloadIsRejectedNotFatal() throws Exception {
        String deviceId = "itgbad" + System.nanoTime();
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();

        GatewayConfig gatewayConfig = GatewayConfig.from(Map.of(
                "MQTT_BROKER_URL", BROKER,
                "GATEWAY_CLIENT_ID", "gw-" + System.nanoTime(),
                "GATEWAY_HTTP_PORT", "0"));

        try (MqttIngestor ingestor = new MqttIngestor(gatewayConfig, registry, metrics);
             MqttSinkFactory sinks = new MqttSinkFactory(MqttConfig.from(
                     Map.of("MQTT_BROKER_URL", BROKER,
                            "MQTT_CLIENT_ID_PREFIX", "itgbad" + System.nanoTime())))) {

            ingestor.start();
            TelemetrySink sink = sinks.create(deviceId);

            byte[] garbage = "{\"deviceId\":".getBytes(StandardCharsets.UTF_8);
            sink.publish(Topics.telemetry(deviceId), garbage, 0, garbage.length);
            awaitUntil(() -> metrics.malformedCount() >= 1L);

            // The gateway must keep serving this device afterwards.
            byte[] good = wireFormatFor(deviceId).getBytes(StandardCharsets.UTF_8);
            sink.publish(Topics.telemetry(deviceId), good, 0, good.length);
            awaitUntil(() -> metrics.acceptedCount() >= 1L);

            DeviceRecord record = registry.find(deviceId).orElseThrow();
            assertEquals(1L, record.telemetryRejected());
            assertEquals(1L, record.telemetryAccepted());
        }
    }

    /**
     * The format-drift guard across the module boundary.
     *
     * <p>The device serializes with a hand-rolled encoder and the gateway
     * parses with Jackson. Nothing in either module's own tests would notice
     * if the two stopped agreeing, and the failure would look like devices
     * going silent rather than like a format change.
     */
    @Test
    @DisplayName("both device variants produce payloads the gateway parses to the same values")
    void deviceOutputRoundTripsThroughTheGatewayParser() throws Exception {
        TelemetryParser parser = new TelemetryParser();
        RecordingSink constrainedSink = new RecordingSink();
        RecordingSink naiveSink = new RecordingSink();

        FailureInjector none = new FailureInjector(FailureMode.NONE, 0L, 1);
        EdgeDevice constrained = new ConstrainedEdgeDevice(
                "device-001", sensor(), constrainedSink, none);
        EdgeDevice naive = new NaiveEdgeDevice("device-001", sensor(), naiveSink, none);

        for (int i = 0; i < 200; i++) {
            long timestamp = 1_787_484_895_182L + i * 1_000L;
            constrained.publishReading(timestamp);
            naive.publishReading(timestamp);
        }

        for (int i = 0; i < 200; i++) {
            byte[] fromConstrained =
                    constrainedSink.payloads().get(i).getBytes(StandardCharsets.UTF_8);
            byte[] fromNaive = naiveSink.payloads().get(i).getBytes(StandardCharsets.UTF_8);

            Telemetry parsedConstrained = parser.parse(fromConstrained, 0, fromConstrained.length);
            Telemetry parsedNaive = parser.parse(fromNaive, 0, fromNaive.length);

            assertEquals(parsedNaive, parsedConstrained, "variants diverged at reading " + i);
            assertEquals("device-001", parsedConstrained.deviceId());
            assertEquals(1_787_484_895_182L + i * 1_000L, parsedConstrained.timestamp());
        }
    }

    private static Presence presenceOf(DeviceRegistry registry, String deviceId) {
        return registry.find(deviceId).map(DeviceRecord::presence).orElse(null);
    }

    private static SensorModel sensor() {
        return new SensorModel(1234L, 52.5200d, 13.4050d);
    }

    private static String wireFormatFor(String deviceId) {
        return "{\"deviceId\":\"" + deviceId + "\",\"ts\":1787484895182,\"temp\":19.90,"
                + "\"vib\":1.01,\"batt\":99.95,\"lat\":52.5235,\"lon\":13.4083,"
                + "\"status\":\"OK\"}";
    }

    private static boolean brokerReachable() {
        URI uri = URI.create(BROKER);
        if (uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalStateException(
                    "MQTT_BROKER_URL is malformed, so these tests cannot run: '" + BROKER + "'");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting condition", e);
            }
        }
        throw new AssertionError("condition not met within 15s");
    }
}
