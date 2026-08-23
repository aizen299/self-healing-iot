package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The full detection cycle, driven by an explicit clock.
 *
 * <p>Time is passed into {@code sweep} rather than waited for, so these cover
 * the whole failure-and-recovery path in milliseconds and without the
 * flakiness that timing-dependent detection tests usually carry.
 */
class HealthMonitorTest {

    private static final long INTERVAL = 1_000L;
    private static final long T0 = 1_787_500_000_000L;

    private final DeviceRegistry registry = new DeviceRegistry();
    private final HealthPolicy policy = new HealthPolicy(INTERVAL, 2, 4, 2);
    private final GatewayMetrics metrics = new GatewayMetrics();
    private final RecordingPublisher published = new RecordingPublisher();
    private final HealthMonitor monitor =
            new HealthMonitor(registry, policy, metrics, published, 1_000L);

    @Test
    @DisplayName("a device that goes quiet walks ONLINE to SUSPECTED to OFFLINE")
    void silenceLeadsToAFailureDeclaration() {
        heartbeat("device-001", T0);
        assertEquals(DeviceHealth.ONLINE, healthOf("device-001"));

        assertEquals(List.of(), monitor.sweep(T0 + 1_500L), "one miss is tolerated");
        assertEquals(DeviceHealth.ONLINE, healthOf("device-001"));

        monitor.sweep(T0 + 2_500L);
        assertEquals(DeviceHealth.SUSPECTED, healthOf("device-001"));
        assertEquals(0L, metrics.failuresDetectedCount(), "suspicion is not a failure");

        monitor.sweep(T0 + 4_500L);
        assertEquals(DeviceHealth.OFFLINE, healthOf("device-001"));
        assertEquals(1L, metrics.failuresDetectedCount());
    }

    @Test
    @DisplayName("only actionable transitions are announced")
    void suspicionIsNotPublished() {
        heartbeat("device-001", T0);
        monitor.sweep(T0 + 2_500L);

        // SUSPECTED is the detector hedging against a lost QoS 0 message.
        // Publishing it would invite consumers to act on a non-failure.
        assertEquals(List.of(), published.events);

        monitor.sweep(T0 + 4_500L);
        assertEquals(1, published.events.size());
        assertEquals(DeviceHealth.OFFLINE, published.events.get(0).to());
    }

    @Test
    void recoveryRequiresConfirmationAndReportsItsDuration() {
        heartbeat("device-001", T0);
        monitor.sweep(T0 + 4_500L);
        assertEquals(DeviceHealth.OFFLINE, healthOf("device-001"));

        heartbeat("device-001", T0 + 6_000L);
        assertEquals(DeviceHealth.RECOVERING, healthOf("device-001"),
                "one heartbeat starts probation, it does not end it");
        assertEquals(0L, metrics.recoveriesObservedCount());

        heartbeat("device-001", T0 + 7_000L);
        assertEquals(DeviceHealth.ONLINE, healthOf("device-001"));
        assertEquals(1L, metrics.recoveriesObservedCount());

        HealthTransition recovery = published.events.get(published.events.size() - 1);
        assertTrue(recovery.isRecovery());
        // Declared failed at T0+4500, confirmed back at T0+7000.
        assertEquals(2_500L, recovery.recoveryDurationMillis(),
                "the interval measured is detection to confirmation");
    }

    @Test
    void aLateDeviceThatReturnsIsNotCountedAsARecovery() {
        heartbeat("device-001", T0);
        monitor.sweep(T0 + 2_500L);
        assertEquals(DeviceHealth.SUSPECTED, healthOf("device-001"));

        heartbeat("device-001", T0 + 2_600L);

        assertEquals(DeviceHealth.ONLINE, healthOf("device-001"));
        assertEquals(0L, metrics.failuresDetectedCount());
        assertEquals(0L, metrics.recoveriesObservedCount(),
                "nothing failed, so nothing recovered");
    }

    @Test
    @DisplayName("a device never heard from is left alone, however long it is silent")
    void ghostDevicesAreNotDeclaredFailed() {
        registry.recordPresence("ghost-001", io.fleet.common.Presence.OFFLINE, T0);

        monitor.sweep(T0 + 1_000_000L);

        assertEquals(DeviceHealth.UNKNOWN, healthOf("ghost-001"));
        assertEquals(0L, metrics.failuresDetectedCount());
        assertEquals(List.of(), published.events);
    }

    @Test
    void telemetryAloneDoesNotKeepADeviceAlive() {
        heartbeat("device-001", T0);
        // A wedged heartbeat path with a publisher still running is exactly the
        // fault this monitor exists for, so readings must not count as liveness.
        registry.recordTelemetry(new io.fleet.common.Telemetry(
                "device-001", T0 + 4_000L, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d,
                io.fleet.common.DeviceStatus.OK), T0 + 4_000L);

        monitor.sweep(T0 + 4_500L);

        assertEquals(DeviceHealth.OFFLINE, healthOf("device-001"));
    }

    @Test
    void sweepReportsEveryDeviceInAStableOrder() {
        heartbeat("device-003", T0);
        heartbeat("device-001", T0);
        heartbeat("device-002", T0);

        List<HealthTransition> transitions = monitor.sweep(T0 + 4_500L);

        assertEquals(
                List.of("device-001", "device-002", "device-003"),
                transitions.stream().map(HealthTransition::deviceId).toList());
    }

    private void heartbeat(String deviceId, long atMillis) {
        registry.recordHeartbeat(deviceId, atMillis, policy).ifPresent(monitor::announce);
    }

    private DeviceHealth healthOf(String deviceId) {
        return registry.find(deviceId).orElseThrow().health();
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<HealthTransition> events = new ArrayList<>();

        @Override
        public void publish(HealthTransition transition) {
            // Mirrors the real publisher's filter: only actionable transitions
            // reach a consumer.
            if (transition.isFailure() || transition.isRecovery()
                    || transition.to() == DeviceHealth.RECOVERING) {
                events.add(transition);
            }
        }
    }
}
