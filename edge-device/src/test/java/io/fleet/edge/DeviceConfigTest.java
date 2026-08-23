package io.fleet.edge;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceConfigTest {

    @Test
    void defaultsToTheScopedFleetSize() {
        DeviceConfig config = DeviceConfig.from(Map.of());

        assertEquals(Variant.CONSTRAINED, config.variant());
        assertEquals(SinkType.COUNTING, config.sink(),
                "the default must need no broker, and must keep transport cost out of Pillar A");
        assertEquals(50, config.deviceCount(), "ADR-003 scopes the fleet to 50 devices");
        assertEquals(FailureMode.NONE, config.failureMode());
        assertEquals(1000L, config.publishIntervalMillis());
    }

    @Test
    void readsEveryValueFromTheEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("FLEET_VARIANT", "naive");
        env.put("FLEET_DEVICE_COUNT", "25");
        env.put("FLEET_DEVICE_ID_PREFIX", "node");
        env.put("FLEET_PUBLISH_INTERVAL_MS", "250");
        env.put("FLEET_RUN_DURATION_SECONDS", "60");
        env.put("FLEET_FAILURE_MODE", "crash");
        env.put("FLEET_FAIL_AFTER", "30");
        env.put("FLEET_SEED", "7");

        DeviceConfig config = DeviceConfig.from(env);

        assertEquals(Variant.NAIVE, config.variant());
        assertEquals(25, config.deviceCount());
        assertEquals("node", config.deviceIdPrefix());
        assertEquals(250L, config.publishIntervalMillis());
        assertEquals(60L, config.runDurationSeconds());
        assertEquals(FailureMode.CRASH, config.failureMode());
        assertEquals(30L, config.failAfterReadings());
        assertEquals(7L, config.seed());
    }

    @Test
    void generatesZeroPaddedDeviceIds() {
        DeviceConfig config = DeviceConfig.from(Map.of());

        assertEquals("device-001", config.deviceId(1));
        assertEquals("device-050", config.deviceId(50));
    }

    @Test
    void derivesADistinctSeedPerDevice() {
        DeviceConfig config = DeviceConfig.from(Map.of("FLEET_SEED", "42"));

        assertEquals(config.seedFor(1), config.seedFor(1), "seeding must be reproducible");
        assertTrue(config.seedFor(1) != config.seedFor(2), "devices must differ from each other");
    }

    @Test
    void rejectsUnparseableNumbers() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "fifty")));
        assertTrue(error.getMessage().contains("FLEET_DEVICE_COUNT"), error.getMessage());
    }

    @Test
    void rejectsUnknownEnumValues() {
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_VARIANT", "optimised")));
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_FAILURE_MODE", "explode")));
    }

    @Test
    void rejectsNonPositiveCountsAndIntervals() {
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "0")));
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_PUBLISH_INTERVAL_MS", "0")));
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_RUN_DURATION_SECONDS", "0")));
    }

    @Test
    void rejectsAFailureThatCouldNeverTrigger() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_FAILURE_MODE", "CRASH")));
        assertTrue(error.getMessage().contains("failAfterReadings"), error.getMessage());
    }

    @Test
    void rejectsAFloodThatWouldNotFlood() {
        assertThrows(ConfigurationException.class, () -> DeviceConfig.from(Map.of(
                "FLEET_FAILURE_MODE", "MESSAGE_FLOOD",
                "FLEET_FAIL_AFTER", "10",
                "FLEET_FLOOD_MULTIPLIER", "1")));
    }

    @Test
    void rejectsValuesTooLargeForAnIntRatherThanWrappingThem() {
        // A bare (int) cast turns this into 1, which passes every range check
        // that follows and silently runs a one-device fleet.
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "4294967297")));
        assertTrue(error.getMessage().contains("32-bit"), error.getMessage());
    }

    @Test
    void rejectsADeviceIdPrefixThatWouldOverflowThePayloadBuffer() {
        String prefix = "n".repeat(DeviceConfig.MAX_DEVICE_ID_PREFIX_LENGTH + 1);

        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_DEVICE_ID_PREFIX", prefix)));
        assertTrue(error.getMessage().contains("deviceIdPrefix"), error.getMessage());
    }

    @Test
    void rejectsNetworkInterruptionWithoutANetwork() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of(
                        "FLEET_FAILURE_MODE", "NETWORK_INTERRUPTION",
                        "FLEET_FAIL_AFTER", "5")));
        assertTrue(error.getMessage().contains("FLEET_SINK=MQTT"), error.getMessage());
    }

    @Test
    void acceptsNetworkInterruptionWithTheMqttSink() {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_SINK", "mqtt",
                "FLEET_FAILURE_MODE", "NETWORK_INTERRUPTION",
                "FLEET_FAIL_AFTER", "5",
                "FLEET_INTERRUPT_DURATION_MS", "1500"));

        assertEquals(SinkType.MQTT, config.sink());
        assertEquals(FailureMode.NETWORK_INTERRUPTION, config.failureMode());
        assertEquals(1500L, config.interruptDurationMillis());
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_BASE_LAT", "91")));
        assertThrows(ConfigurationException.class,
                () -> DeviceConfig.from(Map.of("FLEET_BASE_LON", "-181")));
    }
}
