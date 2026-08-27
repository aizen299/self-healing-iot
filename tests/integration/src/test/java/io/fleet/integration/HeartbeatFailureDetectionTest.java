package io.fleet.integration;

import io.fleet.common.DeviceHealth;
import io.fleet.common.Presence;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.harness.FleetHarness;
import io.fleet.edge.mqtt.MqttConfig;
import io.fleet.edge.mqtt.MqttSinkFactory;
import io.fleet.gateway.DeviceRecord;
import io.fleet.gateway.DeviceRegistry;
import io.fleet.gateway.GatewayConfig;
import io.fleet.gateway.GatewayMetrics;
import io.fleet.gateway.HealthMonitor;
import io.fleet.gateway.MqttIngestor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure detection over a real broker, end to end.
 *
 * <p>The scenario that justifies heartbeat monitoring existing at all: a
 * device whose liveness path has wedged while its connection and its
 * publisher both keep working. The broker has nothing to report — the socket
 * is fine, so no Last Will fires — and telemetry keeps arriving, so a
 * detector watching traffic would see a healthy device. Only the heartbeat
 * timeout notices.
 */
class HeartbeatFailureDetectionTest {

    private static final String BROKER =
            System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://127.0.0.1:1883");

    /** Fast thresholds: fail after 4 missed 200 ms heartbeats, so ~800 ms. */
    private static final String PUBLISH_INTERVAL_MS = "200";

    @BeforeEach
    void requireBroker() {
        Assumptions.assumeTrue(brokerReachable(),
                "no MQTT broker at " + BROKER + " — skipping detection test");
    }

    @Test
    @DisplayName("a wedged heartbeat is detected while the connection and telemetry stay healthy")
    void heartbeatStopIsDetectedWithoutAConnectionLoss() throws Exception {
        String prefix = "hb" + System.nanoTime();
        String deviceId = prefix + "-001";
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        GatewayConfig gatewayConfig = gatewayConfig();

        try (MqttIngestor ingestor = new MqttIngestor(gatewayConfig, registry, metrics);
             HealthMonitor monitor = new HealthMonitor(registry, gatewayConfig.healthPolicy(),
                     metrics, ingestor, gatewayConfig.monitorIntervalMillis())) {

            ingestor.onTransition(monitor::announce);
            ingestor.start();
            monitor.start();

            DeviceConfig deviceConfig = DeviceConfig.from(Map.of(
                    "FLEET_SINK", "mqtt",
                    "FLEET_DEVICE_COUNT", "1",
                    "FLEET_DEVICE_ID_PREFIX", prefix,
                    "FLEET_PUBLISH_INTERVAL_MS", PUBLISH_INTERVAL_MS,
                    "FLEET_FAILURE_MODE", "HEARTBEAT_STOP",
                    "FLEET_FAIL_AFTER", "3"));

            try (MqttSinkFactory sinks = new MqttSinkFactory(MqttConfig.from(
                         Map.of("MQTT_BROKER_URL", BROKER,
                                "MQTT_CLIENT_ID_PREFIX", "hb" + System.nanoTime())));
                 FleetHarness harness = new FleetHarness(deviceConfig, sinks)) {

                harness.start();

                Await.until(deviceId + " to reach ONLINE",
                        () -> healthOf(registry, deviceId) == DeviceHealth.ONLINE,
                        () -> "health is " + healthOf(registry, deviceId));
                Await.until(deviceId + " to be declared OFFLINE by heartbeat timeout",
                        () -> healthOf(registry, deviceId) == DeviceHealth.OFFLINE,
                        () -> "health is " + healthOf(registry, deviceId));

                assertEquals(1L, metrics.failuresDetectedCount());

                DeviceRecord record = registry.find(deviceId).orElseThrow();

                // The three assertions that make this a real test of the
                // heartbeat path rather than of something else noticing.
                assertEquals(Presence.ONLINE, record.presence(),
                        "the connection never dropped, so no Last Will can have fired");

                long acceptedAtFailure = record.telemetryAccepted();
                Await.until("telemetry to keep arriving from the wedged device",
                        () -> registry.find(deviceId).orElseThrow().telemetryAccepted()
                                > acceptedAtFailure,
                        () -> "still at " + acceptedAtFailure + " readings");
                assertTrue(registry.find(deviceId).orElseThrow().telemetryAccepted()
                                > acceptedAtFailure,
                        "telemetry keeps flowing, so traffic alone would have looked healthy");

                assertEquals(DeviceHealth.OFFLINE,
                        registry.find(deviceId).orElseThrow().health(),
                        "continuing telemetry must not resurrect a device that stopped"
                                + " asserting liveness");
            }
        }
    }

    @Test
    @DisplayName("a healthy fleet is never declared failed")
    void healthyDevicesAreLeftAlone() throws Exception {
        String prefix = "ok" + System.nanoTime();
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        GatewayConfig gatewayConfig = gatewayConfig();

        try (MqttIngestor ingestor = new MqttIngestor(gatewayConfig, registry, metrics);
             HealthMonitor monitor = new HealthMonitor(registry, gatewayConfig.healthPolicy(),
                     metrics, ingestor, gatewayConfig.monitorIntervalMillis())) {

            ingestor.onTransition(monitor::announce);
            ingestor.start();
            monitor.start();

            DeviceConfig deviceConfig = DeviceConfig.from(Map.of(
                    "FLEET_SINK", "mqtt",
                    "FLEET_DEVICE_COUNT", "3",
                    "FLEET_DEVICE_ID_PREFIX", prefix,
                    "FLEET_PUBLISH_INTERVAL_MS", PUBLISH_INTERVAL_MS));

            try (MqttSinkFactory sinks = new MqttSinkFactory(MqttConfig.from(
                         Map.of("MQTT_BROKER_URL", BROKER,
                                "MQTT_CLIENT_ID_PREFIX", "ok" + System.nanoTime())));
                 FleetHarness harness = new FleetHarness(deviceConfig, sinks)) {

                harness.start();
                Await.until(prefix + "-003 to reach ONLINE",
                        () -> healthOf(registry, prefix + "-003") == DeviceHealth.ONLINE,
                        () -> "health is " + healthOf(registry, prefix + "-003"));

                // Well past the offline threshold: a false positive here would
                // mean the fleet recovers healthy devices in Phase 9.
                Thread.sleep(gatewayConfig.healthPolicy().offlineThresholdMillis() * 2);

                assertEquals(0L, metrics.failuresDetectedCount(),
                        "a heartbeating fleet must never be declared failed");
                for (int i = 1; i <= 3; i++) {
                    assertNotEquals(DeviceHealth.OFFLINE,
                            healthOf(registry, prefix + "-00" + i));
                }
            }
        }
    }

    @Test
    @DisplayName("a fleet that stops on purpose is not declared failed")
    void cleanShutdownIsNotAFailure() throws Exception {
        // The claim this test exists to check used to live in
        // MqttToGatewayTest, asserted against metrics.failuresDetectedCount()
        // — in a test that builds no HealthMonitor. Nothing there could ever
        // increment that counter, so the assertion passed unconditionally and
        // the behaviour was never covered at all. It needs the monitor, so it
        // belongs here.
        String prefix = "bye" + System.nanoTime();
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        GatewayConfig gatewayConfig = gatewayConfig();

        try (MqttIngestor ingestor = new MqttIngestor(gatewayConfig, registry, metrics);
             HealthMonitor monitor = new HealthMonitor(registry, gatewayConfig.healthPolicy(),
                     metrics, ingestor, gatewayConfig.monitorIntervalMillis())) {

            ingestor.onTransition(monitor::announce);
            ingestor.start();
            monitor.start();

            DeviceConfig deviceConfig = DeviceConfig.from(Map.of(
                    "FLEET_SINK", "mqtt",
                    "FLEET_DEVICE_COUNT", "3",
                    "FLEET_DEVICE_ID_PREFIX", prefix,
                    "FLEET_PUBLISH_INTERVAL_MS", PUBLISH_INTERVAL_MS));

            try (MqttSinkFactory sinks = new MqttSinkFactory(MqttConfig.from(
                         Map.of("MQTT_BROKER_URL", BROKER,
                                "MQTT_CLIENT_ID_PREFIX", "bye" + System.nanoTime())));
                 FleetHarness harness = new FleetHarness(deviceConfig, sinks)) {

                harness.start();
                Await.until(prefix + "-003 to reach ONLINE",
                        () -> healthOf(registry, prefix + "-003") == DeviceHealth.ONLINE,
                        () -> "health is " + healthOf(registry, prefix + "-003"));
            }

            // Closing the harness stops all three deliberately, so each
            // publishes a retained SHUTDOWN rather than dying into its will.
            Await.until("all three devices to publish a retained SHUTDOWN",
                    () -> shutdownCount(registry, prefix) == 3L,
                    () -> shutdownCount(registry, prefix) + " of 3 have");

            // Well past the threshold: a stopped device goes silent, and
            // silence is what the monitor declares failures on. It must not
            // declare one here, because the device said it was leaving —
            // SHUTDOWN retires it rather than putting it under watch.
            Thread.sleep(gatewayConfig.healthPolicy().offlineThresholdMillis() * 2);

            assertEquals(0L, metrics.failuresDetectedCount(),
                    "a fleet stopped on purpose must never be declared failed");
            for (int i = 1; i <= 3; i++) {
                String id = prefix + "-00" + i;
                assertNotEquals(DeviceHealth.OFFLINE, healthOf(registry, id),
                        id + " stopped deliberately and must not be marked OFFLINE");
            }
        }
    }

    /** How many of this test's devices have announced a deliberate stop. */
    private static long shutdownCount(DeviceRegistry registry, String prefix) {
        return registry.all().stream()
                .filter(record -> record.deviceId().startsWith(prefix))
                .filter(record -> record.presence() == Presence.SHUTDOWN)
                .count();
    }

    private static GatewayConfig gatewayConfig() {
        return GatewayConfig.from(Map.of(
                "MQTT_BROKER_URL", BROKER,
                "GATEWAY_CLIENT_ID", "gw-" + System.nanoTime(),
                "GATEWAY_HTTP_PORT", "0",
                "GATEWAY_HEARTBEAT_INTERVAL_MS", PUBLISH_INTERVAL_MS,
                "GATEWAY_MONITOR_INTERVAL_MS", "50"));
    }

    private static DeviceHealth healthOf(DeviceRegistry registry, String deviceId) {
        return registry.find(deviceId).map(DeviceRecord::health).orElse(null);
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

}
