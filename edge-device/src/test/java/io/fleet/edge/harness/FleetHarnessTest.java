package io.fleet.edge.harness;

import io.fleet.common.SinkException;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.Variant;
import io.fleet.edge.sink.CountingSinkFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetHarnessTest {

    @Test
    void everyDeviceInTheFleetPublishes() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "5",
                "FLEET_PUBLISH_INTERVAL_MS", "20"));
        CountingSinkFactory sinks = new CountingSinkFactory();

        try (FleetHarness harness = new FleetHarness(config, sinks)) {
            harness.start();
            awaitUntil(() -> harness.result().readingsPublished() >= 5L);
            FleetRunResult result = harness.stop();

            assertEquals(5, result.deviceCount());
            assertTrue(result.readingsPublished() >= 5L,
                    "expected at least one reading per device, got " + result.readingsPublished());
            assertEquals(result.readingsPublished(), sinks.payloadCount(),
                    "sink must receive exactly what the devices report publishing");
            assertEquals(0L, result.sinkErrors());
            assertEquals(0L, result.unexpectedErrors());
        }
    }

    @Test
    void aCrashedDeviceIsRecordedAndStopsPublishing() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "1",
                "FLEET_PUBLISH_INTERVAL_MS", "10",
                "FLEET_FAILURE_MODE", "CRASH",
                "FLEET_FAIL_AFTER", "3"));
        CountingSinkFactory sinks = new CountingSinkFactory();

        try (FleetHarness harness = new FleetHarness(config, sinks)) {
            harness.start();
            awaitUntil(() -> !harness.result().crashedDevices().isEmpty());
            FleetRunResult result = harness.stop();

            assertEquals(java.util.List.of("device-001"), result.crashedDevices());
            assertEquals(3L, result.readingsPublished(),
                    "a crash after 3 readings must publish exactly 3");
            assertEquals(3L, sinks.payloadCount());
            assertEquals(0L, result.unexpectedErrors(),
                    "a simulated crash is expected, not an unexpected error");
        }
    }

    @Test
    void crashedDeviceListIsSortedForReproducibility() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "5",
                "FLEET_PUBLISH_INTERVAL_MS", "10",
                "FLEET_FAILURE_MODE", "CRASH",
                "FLEET_FAIL_AFTER", "2"));

        try (FleetHarness harness = new FleetHarness(config, new CountingSinkFactory())) {
            harness.start();
            awaitUntil(() -> harness.result().crashedDevices().size() == 5);
            FleetRunResult result = harness.stop();

            assertEquals(
                    java.util.List.of(
                            "device-001", "device-002", "device-003", "device-004", "device-005"),
                    result.crashedDevices(),
                    "order must not depend on the concurrent set's iteration order");
        }
    }

    @Test
    void messageFloodRaisesThePublishRate() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "1",
                "FLEET_PUBLISH_INTERVAL_MS", "10",
                "FLEET_FAILURE_MODE", "MESSAGE_FLOOD",
                "FLEET_FAIL_AFTER", "2",
                "FLEET_FLOOD_MULTIPLIER", "5"));
        CountingSinkFactory sinks = new CountingSinkFactory();

        try (FleetHarness harness = new FleetHarness(config, sinks)) {
            harness.start();
            awaitUntil(() -> harness.result().readingsPublished() >= 12L);
            harness.stop();

            // Two normal ticks, then 5 readings per tick.
            assertTrue(sinks.payloadCount() >= 12L,
                    "flood should outpace the base rate, got " + sinks.payloadCount());
        }
    }

    @Test
    void startingTwiceIsRejected() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "1"));

        try (FleetHarness harness = new FleetHarness(config, new CountingSinkFactory())) {
            harness.start();
            // Double-scheduling would roughly double the real publish rate
            // while the run still reported the configured one.
            assertThrows(IllegalStateException.class, harness::start);
        }
    }

    @Test
    void durationIsNotEpochSizedBeforeStart() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "1"));

        try (FleetHarness harness = new FleetHarness(config, new CountingSinkFactory())) {
            assertTrue(harness.result().durationMillis() < 60_000L,
                    "an unstarted harness must not report the whole Unix epoch as its duration");
        }
    }

    @Test
    void threadPolicyFollowsTheVariant() {
        assertEquals(1, Variant.CONSTRAINED.threadCount(50),
                "the constrained fleet shares one scheduler thread");
        assertEquals(50, Variant.NAIVE.threadCount(50),
                "the naive fleet takes a thread per device");
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting condition", e);
            }
        }
        throw new AssertionError("condition not met within 10s");
    }
}
