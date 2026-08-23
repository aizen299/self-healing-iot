package io.fleet.gateway;

import io.fleet.common.Presence;
import io.fleet.common.Telemetry;

/**
 * What the gateway currently knows about one device.
 *
 * <p>Immutable and replaced atomically, so a reader never observes a
 * half-updated device while an MQTT callback thread is writing.
 *
 * <p>No health state machine here yet: {@code ONLINE -> SUSPECTED -> OFFLINE
 * -> RECOVERING} is derived from heartbeat timing and arrives in Phase 4.
 * What this holds is only what the broker and the device have actually said.
 *
 * @param deviceId              stable device identifier
 * @param presence              last presence reported, or null if never seen
 * @param presenceAtMillis      when that presence arrived; 0 if never
 * @param lastTelemetry         most recent accepted reading, or null
 * @param lastTelemetryAtMillis when it was accepted; 0 if never
 * @param telemetryAccepted     readings accepted from this device
 * @param telemetryRejected     readings rejected as malformed or invalid
 */
public record DeviceRecord(
        String deviceId,
        Presence presence,
        long presenceAtMillis,
        Telemetry lastTelemetry,
        long lastTelemetryAtMillis,
        long telemetryAccepted,
        long telemetryRejected) {

    public static DeviceRecord unknown(String deviceId) {
        return new DeviceRecord(deviceId, null, 0L, null, 0L, 0L, 0L);
    }

    /**
     * True when the gateway has only ever seen presence for this device and
     * never a reading.
     *
     * <p>Worth distinguishing because retained presence outlives the device
     * that published it (ADR-004): a retained OFFLINE proves a device existed
     * once, not that it exists now. Treating such an entry as a live fleet
     * member would inflate every fleet-level count.
     */
    public boolean presenceOnly() {
        return lastTelemetryAtMillis == 0L && presenceAtMillis > 0L;
    }

    public DeviceRecord withTelemetry(Telemetry telemetry, long atMillis) {
        return new DeviceRecord(deviceId, presence, presenceAtMillis,
                telemetry, atMillis, telemetryAccepted + 1, telemetryRejected);
    }

    public DeviceRecord withRejection() {
        return new DeviceRecord(deviceId, presence, presenceAtMillis,
                lastTelemetry, lastTelemetryAtMillis, telemetryAccepted, telemetryRejected + 1);
    }

    public DeviceRecord withPresence(Presence newPresence, long atMillis) {
        return new DeviceRecord(deviceId, newPresence, atMillis,
                lastTelemetry, lastTelemetryAtMillis, telemetryAccepted, telemetryRejected);
    }
}
