package io.fleet.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryValidatorTest {

    private static final long TIMESTAMP = 1_787_480_547_123L;

    @Test
    void acceptsAValidReading() {
        assertDoesNotThrow(() -> TelemetryValidator.validate(valid()));
    }

    @Test
    void rejectsNull() {
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(null));
    }

    @Test
    void rejectsMissingDeviceId() {
        assertThrows(ValidationException.class,
                () -> TelemetryValidator.validate(withDeviceId(null)));
        assertThrows(ValidationException.class,
                () -> TelemetryValidator.validate(withDeviceId("   ")));
    }

    @Test
    void rejectsNonPositiveTimestamp() {
        Telemetry reading = new Telemetry(
                "device-001", 0L, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK);
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(reading));
    }

    @Test
    void rejectsNonFiniteValues() {
        Telemetry nan = new Telemetry(
                "device-001", TIMESTAMP, Double.NaN, 1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK);
        ValidationException error =
                assertThrows(ValidationException.class, () -> TelemetryValidator.validate(nan));
        assertTrue(error.getMessage().contains("temperature"), error.getMessage());
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(new Telemetry(
                "device-001", TIMESTAMP, 500.0d, 1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK)));
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(new Telemetry(
                "device-001", TIMESTAMP, 20.0d, -1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK)));
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(new Telemetry(
                "device-001", TIMESTAMP, 20.0d, 1.0d, 101.0d, 52.52d, 13.405d, DeviceStatus.OK)));
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(new Telemetry(
                "device-001", TIMESTAMP, 20.0d, 1.0d, 90.0d, 91.0d, 13.405d, DeviceStatus.OK)));
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(new Telemetry(
                "device-001", TIMESTAMP, 20.0d, 1.0d, 90.0d, 52.52d, 181.0d, DeviceStatus.OK)));
    }

    @Test
    void rejectsMissingStatus() {
        Telemetry reading = new Telemetry(
                "device-001", TIMESTAMP, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d, null);
        assertThrows(ValidationException.class, () -> TelemetryValidator.validate(reading));
    }

    private static Telemetry valid() {
        return new Telemetry(
                "device-001", TIMESTAMP, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK);
    }

    private static Telemetry withDeviceId(String deviceId) {
        return new Telemetry(
                deviceId, TIMESTAMP, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d, DeviceStatus.OK);
    }
}
