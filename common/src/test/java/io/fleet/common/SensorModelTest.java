package io.fleet.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism here is what makes an experiment replayable, and what lets the
 * two edge-device variants be given provably identical work.
 */
class SensorModelTest {

    @Test
    void sameSeedProducesTheSameSequence() {
        SensorModel left = new SensorModel(99L, 52.52d, 13.405d);
        SensorModel right = new SensorModel(99L, 52.52d, 13.405d);

        for (int i = 0; i < 500; i++) {
            left.advance();
            right.advance();
            assertEquals(left.temperature(), right.temperature(), 0.0d, "temperature at " + i);
            assertEquals(left.vibration(), right.vibration(), 0.0d, "vibration at " + i);
            assertEquals(left.batteryLevel(), right.batteryLevel(), 0.0d, "battery at " + i);
            assertEquals(left.latitude(), right.latitude(), 0.0d, "latitude at " + i);
            assertEquals(left.longitude(), right.longitude(), 0.0d, "longitude at " + i);
        }
    }

    @Test
    void differentSeedsDiverge() {
        SensorModel left = new SensorModel(1L, 52.52d, 13.405d);
        SensorModel right = new SensorModel(2L, 52.52d, 13.405d);
        left.advance();
        right.advance();

        assertNotEquals(left.temperature(), right.temperature());
    }

    @Test
    void zeroSeedDoesNotDegenerate() {
        // xorshift locks at zero if seeded with zero; the model substitutes a
        // constant, so a run configured with seed 0 must still vary.
        SensorModel model = new SensorModel(0L, 0.0d, 0.0d);
        model.advance();
        double first = model.temperature();
        model.advance();

        assertNotEquals(first, model.temperature());
    }

    @Test
    void readingsStayWithinValidatorRanges() {
        SensorModel model = new SensorModel(7L, 52.52d, 13.405d);

        for (int i = 0; i < 2_000; i++) {
            model.advance();
            assertTrue(model.temperature() >= 15.0d && model.temperature() <= 35.0d,
                    "temperature out of band: " + model.temperature());
            assertTrue(model.vibration() >= 0.0d && model.vibration() <= 5.0d,
                    "vibration out of band: " + model.vibration());
            assertTrue(model.batteryLevel() >= 0.0d && model.batteryLevel() <= 100.0d,
                    "battery out of band: " + model.batteryLevel());
        }
    }

    @Test
    void batteryDrainsMonotonicallyAndClampsAtZero() {
        SensorModel model = new SensorModel(3L, 0.0d, 0.0d);
        double previous = 100.0d;

        for (int i = 0; i < 5_000; i++) {
            model.advance();
            assertTrue(model.batteryLevel() <= previous, "battery increased at reading " + i);
            previous = model.batteryLevel();
        }
        assertEquals(0.0d, model.batteryLevel(), 0.0d, "battery must clamp at zero");
    }

    @Test
    void statusDegradesAsBatteryFalls() {
        assertEquals(DeviceStatus.OK, DeviceStatus.classify(100.0d, 0.5d));
        assertEquals(DeviceStatus.DEGRADED, DeviceStatus.classify(20.0d, 0.5d));
        assertEquals(DeviceStatus.CRITICAL, DeviceStatus.classify(5.0d, 0.5d));
        assertEquals(DeviceStatus.DEGRADED, DeviceStatus.classify(100.0d, 4.0d));
        assertEquals(DeviceStatus.CRITICAL, DeviceStatus.classify(100.0d, 4.8d));
    }
}
