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

    /**
     * Records a connection change and returns the health change it caused.
     *
     * <p>The fast detection path: a fired Last Will is proof the device is
     * gone, so there is no reason to wait out a heartbeat timeout as well.
     */
    public Optional<HealthTransition> recordPresence(
            String deviceId, Presence presence, long atMillis, HealthPolicy policy) {

        DeviceHealth[] before = new DeviceHealth[1];
        long[] offlineSince = new long[1];
        Presence[] presenceBefore = new Presence[1];
        DeviceRecord after = devices.compute(deviceId, (id, existing) -> {
            DeviceRecord record = orUnknown(id, existing);
            before[0] = record.health();
            offlineSince[0] = record.offlineSinceMillis();
            presenceBefore[0] = record.presence();
            return record.withPresence(presence, atMillis, policy);
        });

        Optional<HealthTransition> changed =
                transitionOf(before[0], offlineSince[0], after, atMillis, 0);
        if (changed.isPresent()) {
            return changed;
        }
        return deathOfAnAlreadyFailedDevice(
                presenceBefore[0], presence, after, offlineSince[0], atMillis);
    }

    /**
     * A will that fires for a device already recorded as failed.
     *
     * <p>Health is a state and failure detection is edge-triggered on it, so a
     * device that is already {@code OFFLINE} cannot transition to
     * {@code OFFLINE} again and emits nothing. That is right when the device
     * really is the one already reported — and wrong in the case that matters:
     * a device declared offline by a heartbeat timeout that was never true.
     *
     * <p>It happens. A gateway that loses its own broker connection stops
     * receiving heartbeats it should have received, declares the whole fleet
     * failed, and the operator correctly answers "no recovery needed, the pod
     * is Running" for each. Every device is then marked {@code OFFLINE} while
     * alive, and no replacement exists. When one of them genuinely dies, the
     * broker's will is the only evidence anyone gets — and the health state
     * machine swallows it. The device stays dead, unreported and unreplaced,
     * which was observed for two minutes before this existed.
     *
     * <p>The signal is the <em>presence</em> edge rather than the health edge:
     * a device seen {@code ONLINE} whose connection has now dropped. That
     * keeps the two guards this must not break. A retained will replayed to a
     * fresh gateway does not fire, because the device was never seen
     * {@code ONLINE} on that connection — the same reasoning that keeps
     * retained-presence ghosts out of the recovery path (ADR-006). And a
     * redelivered will does not fire twice, because the second one finds the
     * presence already {@code OFFLINE}.
     *
     * <p>The transition it produces is {@code OFFLINE → OFFLINE}, which reads
     * oddly and is accurate: the device was already believed down, and the
     * broker has now confirmed it is gone. Recovery stays idempotent because
     * the operator derives its replacement's name from the detection time
     * (ADR-011), so this asks for a pod the earlier detection never created.
     */
    private static Optional<HealthTransition> deathOfAnAlreadyFailedDevice(
            Presence before, Presence now, DeviceRecord after,
            long offlineSinceBefore, long atMillis) {

        if (before != Presence.ONLINE || now != Presence.OFFLINE
                || after.health() != DeviceHealth.OFFLINE) {
            return Optional.empty();
        }
        return Optional.of(new HealthTransition(after.deviceId(),
                DeviceHealth.OFFLINE, DeviceHealth.OFFLINE, atMillis, 0,
                offlineSinceOf(after, offlineSinceBefore)));
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
        for (Map.Entry<String, DeviceRecord> entry : devices.entrySet()) {
            // Skipped before the compute: afterSilence is specified never to
            // move a device out of UNKNOWN, so evaluating retained-presence
            // ghosts every sweep is work that can only conclude "no change" —
            // and they accumulate with broker history, not with fleet size.
            if (entry.getValue().health() == DeviceHealth.UNKNOWN) {
                continue;
            }
            String deviceId = entry.getKey();
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
    /**
     * When the device went offline, for the transition being announced.
     *
     * <p>One definition, because two would be one too many: this is the value
     * {@code recoveryDurationMillis} subtracts from, so a copy that drifted
     * would give the same recovery two different durations depending on which
     * path announced it. A record that has already been cleared reports 0, and
     * the value carried into this call is then the one still worth reporting.
     */
    private static long offlineSinceOf(DeviceRecord after, long offlineSinceBefore) {
        return after.offlineSinceMillis() > 0L
                ? after.offlineSinceMillis() : offlineSinceBefore;
    }

    private static Optional<HealthTransition> transitionOf(
            DeviceHealth before, long offlineSinceBefore, DeviceRecord after,
            long atMillis, int missed) {

        if (before == after.health()) {
            return Optional.empty();
        }
        return Optional.of(new HealthTransition(
                after.deviceId(), before, after.health(), atMillis, missed,
                offlineSinceOf(after, offlineSinceBefore)));
    }
}
