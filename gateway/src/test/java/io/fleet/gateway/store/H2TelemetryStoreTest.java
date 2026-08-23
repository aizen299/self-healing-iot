package io.fleet.gateway.store;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.common.DeviceStatus;
import io.fleet.common.StoreException;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2TelemetryStoreTest {

    private static final long T0 = 1_787_500_000_000L;

    @TempDir
    Path tempDir;

    private H2TelemetryStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new H2TelemetryStore(config(Map.of("GATEWAY_STORE_PATH", "mem")));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            // Drops the shared in-memory database so tests cannot see each
            // other's rows; DB_CLOSE_DELAY=-1 keeps it alive otherwise.
            store.pruneTelemetryBefore(Long.MAX_VALUE);
            store.close();
        }
    }

    @Test
    void storesAndReadsBackAReading() throws Exception {
        store.record(reading("device-001", T0, 21.5d), T0 + 1L);
        store.flush();

        List<Telemetry> history = store.history("device-001", T0 - 1L, T0 + 1L);

        assertEquals(1, history.size());
        assertEquals(21.5d, history.get(0).temperature(), 1e-9);
        assertEquals(DeviceStatus.OK, history.get(0).status());
    }

    @Test
    @DisplayName("a query flushes first, so it never reads a partly buffered history")
    void queriesSeeBufferedWrites() throws Exception {
        // Well under the batch size, so these are still in the write buffer.
        for (int i = 0; i < 5; i++) {
            store.record(reading("device-001", T0 + i, 20.0d), T0 + i);
        }

        assertEquals(5, store.history("device-001", T0, T0 + 10).size(),
                "a caller must not have to know about the buffer");
        assertEquals(5L, store.readingCount("device-001"));
    }

    @Test
    @DisplayName("several readings in the same millisecond are all kept")
    void floodedReadingsAreNotCollapsed() throws Exception {
        // MESSAGE_FLOOD emits a burst inside one tick, so every reading shares
        // a timestamp. A natural key on (device_id, ts) would reject exactly
        // the traffic a flood experiment exists to record.
        for (int i = 0; i < 10; i++) {
            store.record(reading("device-001", T0, 20.0d + i), T0);
        }
        store.flush();

        assertEquals(10L, store.readingCount("device-001"));
    }

    @Test
    void historyIsScopedToOneDeviceAndWindow() throws Exception {
        store.record(reading("device-001", T0, 10.0d), T0);
        store.record(reading("device-001", T0 + 5_000L, 11.0d), T0 + 5_000L);
        store.record(reading("device-002", T0, 99.0d), T0);
        store.flush();

        List<Telemetry> window = store.history("device-001", T0 - 1L, T0 + 1L);

        assertEquals(1, window.size());
        assertEquals(10.0d, window.get(0).temperature(), 1e-9);
    }

    @Test
    void computesFleetAggregates() throws Exception {
        store.record(reading("device-001", T0, 10.0d), T0);
        store.record(reading("device-002", T0 + 500L, 20.0d), T0 + 500L);
        store.flush();

        assertEquals(15.0d, store.fleetAverageTemperature(T0, T0 + 1_000L).getAsDouble(), 1e-9);
        // Two readings across a one-second window.
        assertEquals(2.0d, store.telemetryRate(T0, T0 + 1_000L), 1e-9);
    }

    @Test
    @DisplayName("an aggregate over no rows is absent, not zero")
    void emptyAggregatesAreDistinguishableFromZero() throws Exception {
        // SQL AVG over no rows is NULL, which getDouble reports as 0.0 —
        // indistinguishable from a fleet genuinely averaging zero degrees.
        assertTrue(store.fleetAverageTemperature(T0, T0 + 1_000L).isEmpty());
        assertTrue(store.meanRecoveryMillis(T0, T0 + 1_000L).isEmpty());
        assertEquals(0.0d, store.telemetryRate(T0, T0 + 1_000L), 1e-9);
    }

    @Test
    void tracksFailuresAndRecoveries() throws Exception {
        store.recordEvent(event("device-001", DeviceEventType.DEVICE_OFFLINE,
                DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE, T0, 4, -1L));
        assertEquals(List.of("device-001"), store.currentlyFailedDevices());

        store.recordEvent(event("device-001", DeviceEventType.DEVICE_RECOVERED,
                DeviceHealth.RECOVERING, DeviceHealth.ONLINE, T0 + 2_500L, 0, 2_500L));

        assertTrue(store.currentlyFailedDevices().isEmpty(),
                "the latest transition decides whether a device is still failed");
        assertEquals(1, store.recoveries(T0, T0 + 10_000L).size());
        assertEquals(2_500.0d,
                store.meanRecoveryMillis(T0, T0 + 10_000L).getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("failed devices are derived from history, so they survive a restart")
    void failedDevicesSurviveAReopen() throws Exception {
        StoreConfig fileConfig = config(Map.of(
                "GATEWAY_STORE_PATH", tempDir.resolve("fleet").toString()));

        try (H2TelemetryStore first = new H2TelemetryStore(fileConfig)) {
            first.record(reading("device-001", T0, 20.0d), T0);
            first.recordEvent(event("device-001", DeviceEventType.DEVICE_OFFLINE,
                    DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE, T0, 4, -1L));
        }

        // The live registry is rebuilt from nothing on restart; the record of
        // what happened is not.
        try (H2TelemetryStore reopened = new H2TelemetryStore(fileConfig)) {
            assertEquals(1L, reopened.readingCount("device-001"),
                    "buffered readings must be flushed on close, not lost");
            assertEquals(List.of("device-001"), reopened.currentlyFailedDevices());
        }
    }

    @Test
    void pruningRemovesOnlyOldReadings() throws Exception {
        store.record(reading("device-001", T0, 10.0d), T0);
        store.record(reading("device-001", T0 + 10_000L, 11.0d), T0 + 10_000L);
        store.flush();

        int removed = store.pruneTelemetryBefore(T0 + 5_000L);

        assertEquals(1, removed);
        assertEquals(1L, store.readingCount("device-001"));
    }

    @Test
    void batchingDoesNotLoseReadings() throws Exception {
        H2TelemetryStore batched = new H2TelemetryStore(config(Map.of(
                "GATEWAY_STORE_PATH", tempDir.resolve("batched").toString(),
                "GATEWAY_STORE_BATCH_SIZE", "10")));
        try (batched) {
            // Deliberately not a multiple of the batch size: the remainder is
            // what a naive implementation loses.
            for (int i = 0; i < 25; i++) {
                batched.record(reading("device-001", T0 + i, 20.0d), T0 + i);
            }
            assertEquals(25L, batched.readingCount("device-001"));
        }
    }

    @Test
    void disabledStoreKeepsNothingButAnswersQueries() throws Exception {
        try (NoOpTelemetryStore disabled = new NoOpTelemetryStore()) {
            disabled.record(reading("device-001", T0, 20.0d), T0);
            disabled.flush();

            // Empty, not an error: no history was kept, which is the honest
            // answer rather than a failure.
            assertTrue(disabled.history("device-001", 0L, Long.MAX_VALUE).isEmpty());
            assertEquals(0L, disabled.readingCount("device-001"));
            assertFalse(disabled.fleetAverageTemperature(0L, Long.MAX_VALUE).isPresent());
        }
    }

    private static StoreConfig config(Map<String, String> overrides) {
        return StoreConfig.from(overrides);
    }

    private static Telemetry reading(String deviceId, long ts, double temperature) {
        return new Telemetry(deviceId, ts, temperature, 1.0d, 90.0d, 52.52d, 13.405d,
                DeviceStatus.OK);
    }

    private static DeviceEventRecord event(
            String deviceId, DeviceEventType type, DeviceHealth from, DeviceHealth to,
            long at, int missed, long duration) {
        return new DeviceEventRecord(deviceId, type, from, to, at, missed, duration);
    }
}
