package io.fleet.gateway.store;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryStore;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A store that keeps nothing, used when persistence is switched off.
 *
 * <p>A null object rather than a nullable field: the write path runs on every
 * reading, and scattering null checks through it would be both noise and an
 * invitation to forget one. Queries return empty rather than throwing, so a
 * caller asking a disabled store gets "no history" instead of an error — the
 * honest answer, since none was kept.
 */
public final class NoOpTelemetryStore implements TelemetryStore {

    @Override
    public void record(Telemetry telemetry, long receivedAtMillis) {
    }

    @Override
    public void recordEvent(DeviceEventRecord event) {
    }

    @Override
    public void flush() {
    }

    @Override
    public List<Telemetry> history(String deviceId, long fromMillis, long toMillis) {
        return List.of();
    }

    @Override
    public OptionalDouble fleetAverageTemperature(long fromMillis, long toMillis) {
        return OptionalDouble.empty();
    }

    @Override
    public double telemetryRate(long fromMillis, long toMillis) {
        return 0.0d;
    }

    @Override
    public List<String> currentlyFailedDevices() {
        return List.of();
    }

    @Override
    public List<DeviceEventRecord> recoveries(long fromMillis, long toMillis) {
        return List.of();
    }

    @Override
    public OptionalDouble meanRecoveryMillis(long fromMillis, long toMillis) {
        return OptionalDouble.empty();
    }

    @Override
    public long readingCount(String deviceId) {
        return 0L;
    }

    @Override
    public int pruneTelemetryBefore(long cutoffMillis) {
        return 0;
    }

    @Override
    public void close() {
    }
}
