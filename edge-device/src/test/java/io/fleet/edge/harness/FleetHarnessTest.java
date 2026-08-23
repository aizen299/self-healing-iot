package io.fleet.edge.harness;

import io.fleet.edge.DeviceConfig;
import io.fleet.edge.Variant;
import io.fleet.edge.sink.CountingSink;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetHarnessTest {

    @Test
    void everyDeviceInTheFleetPublishes() {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "5",
                "FLEET_PUBLISH_INTERVAL_MS", "20"));
        CountingSink sink = new CountingSink();

        try (FleetHarness harness = new FleetHarness(config, sink)) {
            harness.start();
            awaitUntil(() -> harness.result().readingsPublished() >= 5L);
            FleetRunResult result = harness.stop();

            assertEquals(5, result.deviceCount());
            assertTrue(result.readingsPublished() >= 5L,
                    "expected at least one reading per device, got " + result.readingsPublished());
            assertEquals(result.readingsPublished(), sink.payloadCount(),
                    "sink must receive exactly what the devices report publishing");
            assertEquals(0L, result.sinkErrors());
            assertEquals(0L, result.unexpectedErrors());
        }
    }

    @Test
    void aCrashedDeviceIsRecordedAndStopsPublishing() {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "1",
                "FLEET_PUBLISH_INTERVAL_MS", "10",
                "FLEET_FAILURE_MODE", "CRASH",
                "FLEET_FAIL_AFTER", "3"));
        CountingSink sink = new CountingSink();

        try (FleetHarness harness = new FleetHarness(config, sink)) {
            harness.start();
            awaitUntil(() -> !harness.result().crashedDevices().isEmpty());
            FleetRunResult result = harness.stop();

            assertEquals(java.util.List.of("device-001"), result.crashedDevices());
            assertEquals(3L, result.readingsPublished(),
                    "a crash after 3 readings must publish exactly 3");
            assertEquals(3L, sink.payloadCount());
            assertEquals(0L, result.unexpectedErrors(),
                    "a simulated crash is expected, not an unexpected error");
        }
    }

    @Test
    void messageFloodRaisesThePublishRate() {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "1",
                "FLEET_PUBLISH_INTERVAL_MS", "10",
                "FLEET_FAILURE_MODE", "MESSAGE_FLOOD",
                "FLEET_FAIL_AFTER", "2",
                "FLEET_FLOOD_MULTIPLIER", "5"));
        CountingSink sink = new CountingSink();

        try (FleetHarness harness = new FleetHarness(config, sink)) {
            harness.start();
            awaitUntil(() -> harness.result().readingsPublished() >= 12L);
            harness.stop();

            // Two normal ticks, then 5 readings per tick.
            assertTrue(sink.payloadCount() >= 12L,
                    "flood should outpace the base rate, got " + sink.payloadCount());
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
