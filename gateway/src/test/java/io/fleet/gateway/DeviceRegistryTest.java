package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import io.fleet.common.DeviceStatus;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    // --- a will that fires for a device already recorded as failed ---------
    //
    // The case these cover was live for two minutes on a real cluster: the
    // gateway lost its own broker connection, declared three healthy devices
    // failed on missed heartbeats, and when one of them actually died the will
    // was swallowed because OFFLINE cannot transition to OFFLINE.

    @Test
    @DisplayName("a will for a device already marked offline is still reported")
    void aWillIsReportedEvenWhenTheDeviceWasAlreadyMarkedOffline() {
        // Alive and heartbeating, then declared offline by a timeout that was
        // never true — the device is still connected and still publishing.
        registry.recordPresence("device-001", Presence.ONLINE, 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_100L, policy);
        assertEquals(DeviceHealth.ONLINE, health("device-001"));

        registry.evaluateSilence(policy, 1_000L + policy.offlineThresholdMillis() * 2);
        assertEquals(DeviceHealth.OFFLINE, health("device-001"),
                "the false timeout is the precondition, not the thing under test");

        // Now it genuinely dies. This is the only evidence anyone gets.
        HealthTransition transition = registry
                .recordPresence("device-001", Presence.OFFLINE, 9_000L, policy)
                .orElseThrow(() -> new AssertionError(
                        "the will was swallowed: a dead device nothing reports "
                                + "is a dead device nothing replaces"));

        assertTrue(transition.isFailure());
        assertEquals(DeviceHealth.OFFLINE, transition.to());
        assertEquals(9_000L, transition.atMillis(),
                "the detection time is what the replacement's identity derives from");
    }

    @Test
    @DisplayName("a redelivered will does not report the same death twice")
    void aSecondWillForTheSameDeathIsNotReportedAgain() {
        registry.recordPresence("device-001", Presence.ONLINE, 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_100L, policy);
        registry.evaluateSilence(policy, 1_000L + policy.offlineThresholdMillis() * 2);

        assertTrue(registry.recordPresence("device-001", Presence.OFFLINE, 9_000L, policy)
                .isPresent());
        assertTrue(registry.recordPresence("device-001", Presence.OFFLINE, 9_500L, policy)
                        .isEmpty(),
                "presence is already OFFLINE, so there is no new death to report");
    }

    @Test
    @DisplayName("a retained will replayed to a fresh gateway is still not a failure")
    void aRetainedWillForADeviceNeverSeenAliveIsStillIgnored() {
        // The guard this must not break (ADR-006). A gateway that has just
        // started subscribes and is handed every retained presence the broker
        // holds; devices it has never seen alive must not be declared failed.
        assertTrue(registry.recordPresence("ghost-001", Presence.OFFLINE, 500L, policy)
                        .isEmpty(),
                "a device never seen ONLINE has not died on our watch");
        assertEquals(DeviceHealth.UNKNOWN, health("ghost-001"));
    }

    @Test
    @DisplayName("a normal death reports exactly one failure, not two")
    void aWillForAHealthyDeviceReportsOneFailure() {
        registry.recordPresence("device-001", Presence.ONLINE, 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_100L, policy);

        HealthTransition transition = registry
                .recordPresence("device-001", Presence.OFFLINE, 2_000L, policy)
                .orElseThrow();

        // The health edge fires here, so the presence rule must not fire as
        // well and produce a second event for one death.
        assertEquals(DeviceHealth.ONLINE, transition.from());
        assertEquals(DeviceHealth.OFFLINE, transition.to());
    }

    @Test
    @DisplayName("a deliberate shutdown is not a death, however the device was marked")
    void aShutdownIsNeverReportedAsAFailure() {
        registry.recordPresence("device-001", Presence.ONLINE, 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_000L, policy);
        registry.recordHeartbeat("device-001", 1_100L, policy);
        registry.evaluateSilence(policy, 1_000L + policy.offlineThresholdMillis() * 2);

        Optional<HealthTransition> transition =
                registry.recordPresence("device-001", Presence.SHUTDOWN, 9_000L, policy);

        assertTrue(transition.isEmpty() || !transition.get().isFailure(),
                "stopping on purpose is not a failure (ADR-006)");
    }

    private DeviceHealth health(String deviceId) {
        return registry.find(deviceId).orElseThrow().health();
    }

    private static Telemetry reading(String deviceId, double temperature) {
        return new Telemetry(
                deviceId, 1_787_484_895_182L, temperature, 1.0d, 90.0d,
                52.52d, 13.405d, DeviceStatus.OK);
    }
}
