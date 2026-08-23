package io.fleet.common;

/**
 * A single telemetry reading.
 *
 * <p>Immutable, and therefore allocated once per reading. The naive
 * edge-device variant uses this on its hot path; the constrained variant
 * deliberately bypasses it and encodes straight into a reused buffer. That
 * difference is one of the things Pillar A measures, so this type is not a
 * shared hot-path abstraction — it is the conventional representation, used
 * by the naive variant, by tests, and by the gateway from Phase 3.
 *
 * @param deviceId     stable device identifier, e.g. {@code device-001}
 * @param timestamp    reading time, epoch milliseconds
 * @param temperature  degrees Celsius
 * @param vibration    arbitrary sensor units, 0 upward
 * @param batteryLevel percentage, 0..100
 * @param latitude     decimal degrees, -90..90
 * @param longitude    decimal degrees, -180..180
 * @param status       device-reported health
 */
public record Telemetry(
        String deviceId,
        long timestamp,
        double temperature,
        double vibration,
        double batteryLevel,
        double latitude,
        double longitude,
        DeviceStatus status) {
}
