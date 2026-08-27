package io.fleet.integration;

import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.harness.FleetHarness;
import io.fleet.edge.mqtt.MqttConfig;
import io.fleet.edge.mqtt.MqttSinkFactory;
import io.fleet.gateway.DeviceRecord;
import io.fleet.gateway.DeviceRegistry;
import io.fleet.gateway.GatewayConfig;
import io.fleet.gateway.GatewayMetrics;
import io.fleet.gateway.MqttIngestor;
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
import java.util.stream.Collectors;

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
                Await.until("telemetry from all three devices",
                        () -> metrics.acceptedCount() >= 3L,
                        () -> metrics.acceptedCount() + " readings accepted");

                // Asserted while the fleet is still up: presence rides the
                // device's own connection, so once it closes the correct answer
                // becomes OFFLINE.
                Await.until(prefix + "-001 to report ONLINE",
                        () -> presenceOf(registry, prefix + "-001") == Presence.ONLINE,
                        () -> "presence is " + presenceOf(registry, prefix + "-001"));

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
            // retained SHUTDOWN — not the OFFLINE the broker would publish as a
            // will — and the gateway must not read that as a failure.
            //
            // Waited on the whole fleet, not on -001. Presence rides each
            // device's own connection (ADR-004), so three devices publish three
            // SHUTDOWNs independently and one arriving says nothing about the
            // other two. That gap is invisible on an idle laptop and a flaky
            // failure on a loaded CI runner, which is where it showed up.
            Await.until("every device to stop reporting ONLINE",
                    () -> registry.onlineCount() == 0L,
                    () -> "still online: " + stillOnline(registry));

            // Every device, not just -001. A device left at OFFLINE means the
            // broker published its will and the gateway read a deliberate stop
            // as a death — and checking one of three would let that pass on the
            // other two, which is the same one-device gap moved somewhere else.
            for (int i = 1; i <= 3; i++) {
                String id = prefix + "-00" + i;
                assertEquals(Presence.SHUTDOWN, presenceOf(registry, id), id
                        + ": a clean stop is SHUTDOWN, not the will the broker sends for a death");
            }

            // The claim that a deliberate stop is not counted as a failure
            // belongs where a HealthMonitor exists to count one: this test
            // builds only an ingestor, so metrics.failuresDetectedCount() is
            // structurally zero here and asserting it proved nothing. See
            // HeartbeatFailureDetectionTest.cleanShutdownIsNotAFailure.
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
            Await.until("the malformed payload to be rejected",
                    () -> metrics.malformedCount() >= 1L,
                    () -> metrics.malformedCount() + " rejected so far");

            // The gateway must keep serving this device afterwards.
            byte[] good = wireFormatFor(deviceId).getBytes(StandardCharsets.UTF_8);
            sink.publish(Topics.telemetry(deviceId), good, 0, good.length);
            Await.until("a good reading after the malformed one",
                    () -> metrics.acceptedCount() >= 1L,
                    () -> metrics.acceptedCount() + " readings accepted");

            DeviceRecord record = registry.find(deviceId).orElseThrow();
            assertEquals(1L, record.telemetryRejected());
            assertEquals(1L, record.telemetryAccepted());
        }
    }

    private static Presence presenceOf(DeviceRegistry registry, String deviceId) {
        return registry.find(deviceId).map(DeviceRecord::presence).orElse(null);
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

    /** Which devices are still ONLINE, for a timeout that has to explain itself. */
    private static String stillOnline(DeviceRegistry registry) {
        return registry.all().stream()
                .filter(record -> record.presence() == Presence.ONLINE)
                .map(DeviceRecord::deviceId)
                .collect(Collectors.joining(", "));
    }
}
