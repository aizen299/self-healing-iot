package io.fleet.common;

/**
 * Range and well-formedness checks for telemetry.
 *
 * <p>Used by the gateway from Phase 3 to reject malformed readings rather
 * than forward them downstream, and by the simulator's tests to prove the
 * generated data stays in range.
 */
public final class TelemetryValidator {

    public static final double MIN_TEMPERATURE_C = -60.0;
    public static final double MAX_TEMPERATURE_C = 120.0;
    public static final double MAX_VIBRATION = 100.0;

    /**
     * @throws ValidationException describing the first field that failed
     */
    public static void validate(Telemetry telemetry) throws ValidationException {
        if (telemetry == null) {
            throw new ValidationException("telemetry is null");
        }
        if (telemetry.deviceId() == null || telemetry.deviceId().isBlank()) {
            throw new ValidationException("deviceId is missing");
        }
        if (telemetry.timestamp() <= 0L) {
            throw new ValidationException(
                    "timestamp must be positive epoch millis, got " + telemetry.timestamp());
        }
        if (telemetry.status() == null) {
            throw new ValidationException("status is missing");
        }
        requireFinite(telemetry.temperature(), "temperature");
        requireFinite(telemetry.vibration(), "vibration");
        requireFinite(telemetry.batteryLevel(), "batteryLevel");
        requireFinite(telemetry.latitude(), "latitude");
        requireFinite(telemetry.longitude(), "longitude");

        requireInRange(telemetry.temperature(), MIN_TEMPERATURE_C, MAX_TEMPERATURE_C, "temperature");
        requireInRange(telemetry.vibration(), 0.0, MAX_VIBRATION, "vibration");
        requireInRange(telemetry.batteryLevel(), 0.0, 100.0, "batteryLevel");
        requireInRange(telemetry.latitude(), -90.0, 90.0, "latitude");
        requireInRange(telemetry.longitude(), -180.0, 180.0, "longitude");
    }

    private static void requireFinite(double value, String field) throws ValidationException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ValidationException(field + " is not finite: " + value);
        }
    }

    private static void requireInRange(double value, double min, double max, String field)
            throws ValidationException {
        if (value < min || value > max) {
            throw new ValidationException(
                    field + " out of range [" + min + ", " + max + "]: " + value);
        }
    }

    private TelemetryValidator() {
    }
}
