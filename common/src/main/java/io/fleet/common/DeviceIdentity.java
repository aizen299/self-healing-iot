package io.fleet.common;

/**
 * Static identity of a simulated device. Established once at construction
 * and never mutated, so it costs nothing on the telemetry hot path.
 *
 * @param deviceId        stable identifier, e.g. {@code device-001}
 * @param deviceType      hardware class, e.g. {@code sensor-node}
 * @param firmwareVersion semantic version string
 * @param location        human-readable site label
 * @param createdAt       epoch milliseconds
 */
public record DeviceIdentity(
        String deviceId,
        String deviceType,
        String firmwareVersion,
        String location,
        long createdAt) {
}
