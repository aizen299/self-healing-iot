package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;

/**
 * What the gateway currently knows about one device.
 *
 * <p>Immutable and replaced atomically, so a reader never observes a
 * half-updated device while an MQTT callback thread is writing.
 *
 * <p>Three separate notions of state live here on purpose:
 * {@code lastTelemetry.status()} is the device's opinion of its own sensors,
 * {@code presence} is the broker's account of the connection, and
 * {@code health} is the gateway's own judgement, drawn from both heartbeat
 * timing and what the broker reports. Only the last is an inference, and only
 * the last drives recovery.
 *
 * @param deviceId               stable device identifier
 * @param health                 the gateway's judgement
 * @param healthChangedAtMillis  when that judgement last changed
 * @param offlineSinceMillis     when the current failure was declared; 0 if healthy
 * @param lastHeartbeatAtMillis  gateway receipt time of the last heartbeat; 0 if never
 * @param consecutiveHeartbeats  heartbeats since the last gap
 * @param presence               last presence reported, or null if never seen
 * @param presenceAtMillis       when that presence arrived; 0 if never
 * @param lastTelemetry          most recent accepted reading, or null
 * @param lastTelemetryAtMillis  when it was accepted; 0 if never
 * @param telemetryAccepted      readings accepted from this device
 * @param telemetryRejected      readings rejected as malformed or invalid
 */
public record DeviceRecord(
        String deviceId,
        DeviceHealth health,
        long healthChangedAtMillis,
        long offlineSinceMillis,
        long lastHeartbeatAtMillis,
        int consecutiveHeartbeats,
        Presence presence,
        long presenceAtMillis,
        Telemetry lastTelemetry,
        long lastTelemetryAtMillis,
        long telemetryAccepted,
        long telemetryRejected) {

    public static DeviceRecord unknown(String deviceId) {
        return new DeviceRecord(deviceId, DeviceHealth.UNKNOWN, 0L, 0L, 0L, 0,
                null, 0L, null, 0L, 0L, 0L);
    }

    /**
     * True when the gateway has only ever seen presence for this device and
     * never a reading or a heartbeat.
     *
     * <p>Worth distinguishing because retained presence outlives the device
     * that published it (ADR-004): a retained OFFLINE proves a device existed
     * once, not that it exists now. Treating such an entry as a live fleet
     * member would inflate every fleet-level count — and, now that failures
     * are detected, would invite recovery of a device that never existed.
     */
    public boolean presenceOnly() {
        return lastTelemetryAtMillis == 0L && lastHeartbeatAtMillis == 0L && presenceAtMillis > 0L;
    }

    public DeviceRecord withTelemetry(Telemetry telemetry, long atMillis) {
        return new DeviceRecord(deviceId, health, healthChangedAtMillis, offlineSinceMillis,
                lastHeartbeatAtMillis, consecutiveHeartbeats, presence, presenceAtMillis,
                telemetry, atMillis, telemetryAccepted + 1, telemetryRejected);
    }

    public DeviceRecord withRejection() {
        return new DeviceRecord(deviceId, health, healthChangedAtMillis, offlineSinceMillis,
                lastHeartbeatAtMillis, consecutiveHeartbeats, presence, presenceAtMillis,
                lastTelemetry, lastTelemetryAtMillis, telemetryAccepted, telemetryRejected + 1);
    }

    /**
     * Records a connection change and the health it implies.
     *
     * <p>A device that shut down deliberately has its heartbeat history
     * cleared as well as its health reset: leaving the last heartbeat in place
     * would let the next sweep count the silence since it left and declare a
     * failure for a device that was stopped on purpose.
     */
    public DeviceRecord withPresence(Presence newPresence, long atMillis, HealthPolicy policy) {
        DeviceHealth next = policy.afterPresence(health, newPresence);
        boolean retired = newPresence == Presence.SHUTDOWN;

        // A will that fires for a device we had seen connected is fresh
        // evidence of a death, whatever we already believed about its health.
        // That distinction decides where the offline clock starts, and the
        // clock is what MTTR is measured from.
        boolean confirmedDeath =
                presence == Presence.ONLINE && newPresence == Presence.OFFLINE;

        return new DeviceRecord(deviceId, next, changedAt(next, atMillis),
                offlineSince(next, atMillis, confirmedDeath),
                retired ? 0L : lastHeartbeatAtMillis,
                retired ? 0 : consecutiveHeartbeats,
                newPresence, atMillis,
                lastTelemetry, lastTelemetryAtMillis, telemetryAccepted, telemetryRejected);
    }

    /** Records a heartbeat's arrival and the health it implies. */
    public DeviceRecord withHeartbeat(long atMillis, HealthPolicy policy) {
        int consecutive = consecutiveHeartbeats + 1;
        DeviceHealth next = policy.afterHeartbeat(health, consecutive);
        return new DeviceRecord(deviceId, next, changedAt(next, atMillis),
                offlineSince(next, atMillis), atMillis, consecutive, presence, presenceAtMillis,
                lastTelemetry, lastTelemetryAtMillis, telemetryAccepted, telemetryRejected);
    }

    /** Applies a health state reached without a heartbeat. */
    public DeviceRecord withHealth(DeviceHealth next, long atMillis) {
        // The streak resets whenever the device is no longer trusted, so a
        // device that flaps cannot accumulate confirmations across outages.
        int consecutive = next.isHealthy() || next == DeviceHealth.RECOVERING
                ? consecutiveHeartbeats : 0;
        return new DeviceRecord(deviceId, next, changedAt(next, atMillis),
                offlineSince(next, atMillis), lastHeartbeatAtMillis, consecutive,
                presence, presenceAtMillis, lastTelemetry, lastTelemetryAtMillis,
                telemetryAccepted, telemetryRejected);
    }

    private long changedAt(DeviceHealth next, long atMillis) {
        return next == health ? healthChangedAtMillis : atMillis;
    }

    private long offlineSince(DeviceHealth next, long atMillis) {
        return offlineSince(next, atMillis, false);
    }

    /**
     * Kept from the moment of failure until the device is confirmed healthy
     * again — including through RECOVERING, so the duration is still available
     * when the recovery completes.
     *
     * <p>{@code confirmedDeath} restarts it. A device already in
     * {@code OFFLINE} normally keeps its original detection time, which is
     * right while that detection is the one still being answered. It is wrong
     * once the broker's will has confirmed a <em>new</em> death: the earlier
     * detection may have been a false timeout that produced no replacement
     * (ADR-006, amended), and the recovery that follows this one would then be
     * timed from a moment minutes earlier — inflating the very number Pillar B
     * reports as MTTR.
     */
    private long offlineSince(DeviceHealth next, long atMillis, boolean confirmedDeath) {
        if (next == DeviceHealth.OFFLINE) {
            boolean keepExisting = health == DeviceHealth.OFFLINE && !confirmedDeath;
            return keepExisting ? offlineSinceMillis : atMillis;
        }
        return next.isHealthy() ? 0L : offlineSinceMillis;
    }
}
