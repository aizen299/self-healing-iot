package io.fleet.gateway;

import io.fleet.common.DeviceStatus;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceRegistryTest {

    private final DeviceRegistry registry = new DeviceRegistry();
    private final HealthPolicy policy = new HealthPolicy(1_000L, 2, 4, 2);


    @Test
    void recordsTelemetryAgainstTheRightDevice() {
        registry.recordTelemetry(reading("device-001", 20.0d), 1_000L);
        registry.recordTelemetry(reading("device-001", 21.0d), 2_000L);
        registry.recordTelemetry(reading("device-002", 22.0d), 3_000L);

        DeviceRecord first = registry.find("device-001").orElseThrow();
        assertEquals(2L, first.telemetryAccepted());
        assertEquals(2_000L, first.lastTelemetryAtMillis());
        assertEquals(21.0d, first.lastTelemetry().temperature(), 1e-9);
        assertEquals(2, registry.size());
    }

    @Test
    void rejectionsAreCountedWithoutDisturbingTheLastGoodReading() {
        registry.recordTelemetry(reading("device-001", 20.0d), 1_000L);
        registry.recordRejection("device-001");

        DeviceRecord record = registry.find("device-001").orElseThrow();
        assertEquals(1L, record.telemetryAccepted());
        assertEquals(1L, record.telemetryRejected());
        assertEquals(20.0d, record.lastTelemetry().temperature(), 1e-9,
                "a rejected reading must not overwrite the last good one");
    }

    @Test
    @DisplayName("presence alone does not make a device a reporting member of the fleet")
    void presenceOnlyDevicesAreDistinguished() {
        // Retained presence outlives the device that set it (ADR-004), so a
        // status message is not evidence the device currently exists.
        registry.recordPresence("ghost-001", Presence.OFFLINE, 500L, policy);
        registry.recordTelemetry(reading("device-001", 20.0d), 1_000L);
        registry.recordPresence("device-001", Presence.ONLINE, 1_100L, policy);

        assertTrue(registry.find("ghost-001").orElseThrow().presenceOnly());
        assertFalse(registry.find("device-001").orElseThrow().presenceOnly());
        assertEquals(2, registry.size());
        assertEquals(1L, registry.reportingCount(), "only one device has actually reported");
        assertEquals(1L, registry.onlineCount());
    }

    @Test
    void telemetryAfterPresenceClearsThePresenceOnlyFlag() {
        registry.recordPresence("device-001", Presence.ONLINE, 500L, policy);
        assertTrue(registry.find("device-001").orElseThrow().presenceOnly());

        registry.recordTelemetry(reading("device-001", 20.0d), 900L);

        assertFalse(registry.find("device-001").orElseThrow().presenceOnly());
    }

    @Test
    void unknownDevicesAreAbsentRatherThanEmpty() {
        assertTrue(registry.find("nobody").isEmpty());
    }

    @Test
    void listingIsSortedSoIdenticalFleetsReportIdentically() {
        registry.recordTelemetry(reading("device-003", 20.0d), 1L);
        registry.recordTelemetry(reading("device-001", 20.0d), 2L);
        registry.recordTelemetry(reading("device-002", 20.0d), 3L);

        assertEquals(
                List.of("device-001", "device-002", "device-003"),
                registry.all().stream().map(DeviceRecord::deviceId).toList());
    }

    private static Telemetry reading(String deviceId, double temperature) {
        return new Telemetry(
                deviceId, 1_787_484_895_182L, temperature, 1.0d, 90.0d,
                52.52d, 13.405d, DeviceStatus.OK);
    }
}
