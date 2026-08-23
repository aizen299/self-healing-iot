package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The gateway's view of the fleet, and the place health transitions happen.
 *
 * <p>Updates arrive on MQTT callback threads, the monitor thread sweeps for
 * silence, and HTTP handler threads read — so every mutation goes through
 * {@link ConcurrentMap#compute}, which applies the change atomically per
 * device. Records are immutable, so a reader either sees the old one or the
 * new one and never a torn state.
 *
 * <p>Mutations return the transition they caused rather than publishing
 * anything themselves. Emitting an event from inside {@code compute} would
 * hold a bin lock across a network call, and would make the registry depend
 * on the broker.
 */
public final class DeviceRegistry {

    private final ConcurrentMap<String, DeviceRecord> devices = new ConcurrentHashMap<>();

    public void recordTelemetry(Telemetry telemetry, long receivedAtMillis) {
        devices.compute(telemetry.deviceId(), (id, existing) ->
                orUnknown(id, existing).withTelemetry(telemetry, receivedAtMillis));
    }

    public void recordRejection(String deviceId) {
        devices.compute(deviceId, (id, existing) -> orUnknown(id, existing).withRejection());
    }

    public void recordPresence(String deviceId, Presence presence, long atMillis) {
        devices.compute(deviceId, (id, existing) ->
                orUnknown(id, existing).withPresence(presence, atMillis));
    }

    /**
     * Records a heartbeat and returns the health change it caused, if any.
     *
     * <p>Telemetry deliberately does <em>not</em> count as liveness. A device
     * whose heartbeat path has wedged while its publisher keeps running is the
     * exact fault heartbeat monitoring exists to catch, and treating any
     * traffic as proof of life would make that fault undetectable.
     */
    public Optional<HealthTransition> recordHeartbeat(
            String deviceId, long receivedAtMillis, HealthPolicy policy) {

        DeviceHealth[] before = new DeviceHealth[1];
        long[] offlineSince = new long[1];
        DeviceRecord after = devices.compute(deviceId, (id, existing) -> {
            DeviceRecord record = orUnknown(id, existing);
            before[0] = record.health();
            offlineSince[0] = record.offlineSinceMillis();
            return record.withHeartbeat(receivedAtMillis, policy);
        });
        return transitionOf(before[0], offlineSince[0], after, receivedAtMillis, 0);
    }

    /**
     * Sweeps every device for silence and returns the resulting transitions.
     *
     * <p>Devices in {@code UNKNOWN} are left alone: silence from something
     * that has never introduced itself is not a failure, which is what keeps
     * a retained-presence ghost from being declared down and recovered.
     */
    public List<HealthTransition> evaluateSilence(HealthPolicy policy, long nowMillis) {
        List<HealthTransition> transitions = new ArrayList<>();
        for (String deviceId : devices.keySet()) {
            DeviceHealth[] before = new DeviceHealth[1];
            long[] offlineSince = new long[1];
            int[] missed = new int[1];
            DeviceRecord after = devices.computeIfPresent(deviceId, (id, record) -> {
                before[0] = record.health();
                offlineSince[0] = record.offlineSinceMillis();
                missed[0] = policy.missedHeartbeats(record.lastHeartbeatAtMillis(), nowMillis);
                DeviceHealth next = policy.afterSilence(record.health(), missed[0]);
                return next == record.health() ? record : record.withHealth(next, nowMillis);
            });
            if (after != null) {
                transitionOf(before[0], offlineSince[0], after, nowMillis, missed[0])
                        .ifPresent(transitions::add);
            }
        }
        // Sorted so an identical fleet reports an identical sequence.
        transitions.sort(Comparator.comparing(HealthTransition::deviceId));
        return transitions;
    }

    public Optional<DeviceRecord> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    /** Every known device, ordered by id so identical fleets report identically. */
    public List<DeviceRecord> all() {
        List<DeviceRecord> snapshot = new ArrayList<>(devices.values());
        snapshot.sort(Comparator.comparing(DeviceRecord::deviceId));
        return snapshot;
    }

    public int size() {
        return devices.size();
    }

    /** Devices the gateway has actually heard from, rather than ghosts. */
    public long reportingCount() {
        return devices.values().stream().filter(record -> !record.presenceOnly()).count();
    }

    /** Devices the broker last reported as connected. */
    public long onlineCount() {
        return devices.values().stream()
                .filter(record -> record.presence() == Presence.ONLINE)
                .count();
    }

    /** Device counts by health, with every state present so callers need no defaults. */
    public Map<DeviceHealth, Long> healthCounts() {
        Map<DeviceHealth, Long> counts = new EnumMap<>(DeviceHealth.class);
        for (DeviceHealth health : DeviceHealth.values()) {
            counts.put(health, 0L);
        }
        for (DeviceRecord record : devices.values()) {
            counts.merge(record.health(), 1L, Long::sum);
        }
        return counts;
    }

    private static DeviceRecord orUnknown(String deviceId, DeviceRecord existing) {
        return existing == null ? DeviceRecord.unknown(deviceId) : existing;
    }

    /**
     * Builds the transition, preferring the record's own failure timestamp but
     * falling back to the one captured before the update.
     *
     * <p>The fallback is what makes recovery duration measurable: confirming a
     * recovery clears {@code offlineSinceMillis} on the record, so without
     * capturing it first the elapsed time would be discarded at the exact
     * moment it becomes meaningful.
     */
    private static Optional<HealthTransition> transitionOf(
            DeviceHealth before, long offlineSinceBefore, DeviceRecord after,
            long atMillis, int missed) {

        if (before == after.health()) {
            return Optional.empty();
        }
        long offlineSince = after.offlineSinceMillis() > 0L
                ? after.offlineSinceMillis() : offlineSinceBefore;
        return Optional.of(new HealthTransition(
                after.deviceId(), before, after.health(), atMillis, missed, offlineSince));
    }
}
