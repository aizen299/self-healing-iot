package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import io.fleet.common.DeviceStatus;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one scrape of the gateway says.
 *
 * <p>The interesting cases are the ones where the exposition would be
 * <em>valid</em> but wrong: a histogram whose buckets do not add up, a state
 * that vanishes when its count reaches zero, and the ghost devices that make
 * "how many devices are there" two different questions.
 */
class MetricsExporterTest {

    private static final long T0 = 1_787_600_000_000L;

    private final DeviceRegistry registry = new DeviceRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics();
    private final HealthPolicy policy = new HealthPolicy(1_000L, 2, 4, 2);

    private String render(boolean brokerConnected, long kafkaFailures) {
        return new MetricsExporter(registry, metrics, () -> brokerConnected,
                () -> kafkaFailures).render();
    }

    private static Telemetry reading(String deviceId) {
        return new Telemetry(deviceId, T0, 20.5d, 1.0d, 90.0d, 52.52d, 13.405d,
                DeviceStatus.OK);
    }

    @Test
    @DisplayName("every health state is present, including the ones at zero")
    void emitsZeroSeriesToo() {
        // A panel whose series simply disappears when the count reaches zero
        // reads as "No data" in Grafana, which looks like a broken scrape
        // rather than a fleet with nothing offline.
        registry.recordTelemetry(reading("device-001"), T0);

        String text = render(true, 0L);

        for (DeviceHealth health : DeviceHealth.values()) {
            assertTrue(text.contains("fleet_devices{state=\"" + health.name() + "\"}"),
                    health + " missing from: " + text);
        }
        assertTrue(text.contains("fleet_devices{state=\"OFFLINE\"} 0"), text);
    }

    @Test
    @DisplayName("ghosts are counted as known and not as reporting")
    void separatesGhostsFromTheRealFleet() {
        // Retained presence outlives the device that set it (ADR-004). A
        // dashboard built on fleet_devices_known counts devices that do not
        // exist, which is why both numbers are exposed.
        registry.recordPresence("ghost-001", Presence.OFFLINE, T0, policy);
        registry.recordTelemetry(reading("device-001"), T0 + 100L);

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_devices_known 2"), text);
        assertTrue(text.contains("fleet_devices_reporting 1"), text);
    }

    @Test
    @DisplayName("a device that is not ONLINE reports up 0 rather than disappearing")
    void reportsEveryDeviceUpOrDown() {
        registry.recordTelemetry(reading("device-001"), T0);
        registry.recordHeartbeat("device-001", T0, policy);
        registry.recordTelemetry(reading("device-002"), T0);

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_device_up{device_id=\"device-001\"} 1"), text);
        assertTrue(text.contains("fleet_device_up{device_id=\"device-002\"} 0"), text);
    }

    @Test
    @DisplayName("the histogram is cumulative and its +Inf bucket equals its count")
    void buildsAConsistentHistogram() {
        // Prometheus reads a +Inf bucket smaller than _count as buckets
        // missing, and non-decreasing buckets are the format's own rule. Both
        // are arithmetic this class does, so both are worth pinning.
        metrics.recoveryObserved(400L);      // <= 500
        metrics.recoveryObserved(1_300L);    // <= 2000
        metrics.recoveryObserved(45_000L);   // <= 60000
        metrics.recoveryObserved(90_000L);   // +Inf only

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"500\"} 1"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"1000\"} 1"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"2000\"} 2"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"30000\"} 2"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"60000\"} 3"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"+Inf\"} 4"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_count 4"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_sum 136700"), text);
    }

    @Test
    @DisplayName("a recovery with no measurable duration is counted but not timed")
    void doesNotLetAnUntimedRecoveryBreakTheHistogram() {
        // The gateway's clock and the operator's can disagree. Such a recovery
        // still happened, so fleet_recoveries_observed_total counts it — but
        // putting it in the histogram would make _count exceed the +Inf
        // bucket, which is an inconsistent histogram.
        metrics.recoveryObserved(1_200L);
        metrics.recoveryObserved(-5L);

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_recoveries_observed_total 2"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_bucket{le=\"+Inf\"} 1"), text);
        assertTrue(text.contains("fleet_recovery_duration_millis_count 1"), text);
    }

    @Test
    @DisplayName("a hostile device id cannot break the scrape")
    void escapesDeviceIdsFromTheWire() {
        // Device ids come off MQTT topics. One malformed line discards the
        // whole scrape, so this would take every other metric with it.
        registry.recordTelemetry(reading("dev\"ice"), T0);

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_device_up{device_id=\"dev\\\"ice\"} 0"), text);
        assertFalse(text.contains("fleet_device_up{device_id=\"dev\"ice\"}"), text);
    }

    @Test
    @DisplayName("broker connectivity is a gauge, both ways")
    void reportsBrokerConnectivity() {
        assertTrue(render(true, 0L).contains("fleet_gateway_broker_connected 1"));
        assertTrue(render(false, 0L).contains("fleet_gateway_broker_connected 0"));
    }

    @Test
    @DisplayName("rejections keep their causes apart")
    void splitsRejectionsByCause() {
        metrics.telemetryMalformed();
        metrics.telemetryInvalid();
        metrics.telemetryInvalid();

        String text = render(true, 0L);

        assertTrue(text.contains("fleet_telemetry_rejected_total{reason=\"malformed\"} 1"), text);
        assertTrue(text.contains("fleet_telemetry_rejected_total{reason=\"invalid\"} 2"), text);
    }

    @Test
    @DisplayName("the forwarder's drop count is exposed, so lost telemetry is visible")
    void reportsKafkaDrops() {
        assertTrue(render(true, 17L)
                .contains("fleet_gateway_kafka_forward_failures_total 17"));
    }

    @Test
    @DisplayName("the JVM series are there and named for this project, not Micrometer")
    void exposesJvmNumbersUnderTheFleetPrefix() {
        // Naming them jvm_memory_used_bytes would claim compatibility with a
        // schema a community dashboard expects and these four numbers cannot
        // satisfy.
        String text = render(true, 0L);

        assertTrue(text.contains("fleet_jvm_heap_used_bytes "), text);
        assertTrue(text.contains("fleet_jvm_threads "), text);
        assertTrue(text.contains("fleet_jvm_gc_collections_total{gc="), text);
        assertFalse(text.contains("jvm_memory_used_bytes"), text);
    }

    @Test
    @DisplayName("every declared name has at least one sample")
    void declaresNothingItDoesNotSample() {
        // A # TYPE with no series under it is a metric that silently never
        // appears in Prometheus — the panel shows No data and nothing says why.
        registry.recordTelemetry(reading("device-001"), T0);
        String text = render(true, 0L);

        // The suffixes are exact rather than "starts with name + underscore":
        // a loose prefix match would let fleet_devices be satisfied by
        // fleet_devices_known, which is a different metric.
        String[] suffixes = {"", "_bucket", "_sum", "_count"};
        text.lines()
                .filter(line -> line.startsWith("# TYPE "))
                .map(line -> line.split(" ")[2])
                .forEach(name -> assertTrue(
                        text.lines().filter(line -> !line.startsWith("#"))
                                .anyMatch(line -> {
                                    for (String suffix : suffixes) {
                                        String series = name + suffix;
                                        if (line.startsWith(series + " ")
                                                || line.startsWith(series + "{")) {
                                            return true;
                                        }
                                    }
                                    return false;
                                }),
                        "declared but never sampled: " + name));
    }
}
