package io.fleet.common;

/**
 * Device-reported health, carried in the telemetry payload.
 *
 * <p>Distinct from the gateway's device state machine
 * ({@code ONLINE -> SUSPECTED -> OFFLINE -> RECOVERING}), which is derived
 * from heartbeat timing rather than self-reported and arrives in Phase 4.
 */
public enum DeviceStatus {
    OK,
    DEGRADED,
    CRITICAL;

    /**
     * Classifies a reading. Both edge-device variants call this so the
     * status field can never diverge between them; see ADR-003.
     *
     * <p>Allocation-free: enum constants are interned.
     */
    public static DeviceStatus classify(double batteryLevel, double vibration) {
        if (batteryLevel < 10.0 || vibration > 4.5) {
            return CRITICAL;
        }
        if (batteryLevel < 25.0 || vibration > 3.5) {
            return DEGRADED;
        }
        return OK;
    }
}
