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
import java.util.function.BooleanSupplier;

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

                awaitUntil(() -> healthOf(registry, deviceId) == DeviceHealth.ONLINE);
                awaitUntil(() -> healthOf(registry, deviceId) == DeviceHealth.OFFLINE);

                assertEquals(1L, metrics.failuresDetectedCount());

                DeviceRecord record = registry.find(deviceId).orElseThrow();

                // The three assertions that make this a real test of the
                // heartbeat path rather than of something else noticing.
                assertEquals(Presence.ONLINE, record.presence(),
                        "the connection never dropped, so no Last Will can have fired");

                long acceptedAtFailure = record.telemetryAccepted();
                awaitUntil(() -> registry.find(deviceId).orElseThrow().telemetryAccepted()
                        > acceptedAtFailure);
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
                awaitUntil(() -> healthOf(registry, prefix + "-003") == DeviceHealth.ONLINE);

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
