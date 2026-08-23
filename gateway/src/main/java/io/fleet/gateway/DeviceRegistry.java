package io.fleet.gateway;

import io.fleet.common.Presence;
import io.fleet.common.Telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The gateway's view of the fleet.
 *
 * <p>Updates arrive on MQTT callback threads and reads come from HTTP handler
 * threads, so every mutation goes through {@link ConcurrentMap#compute},
 * which applies the change atomically per device. Records are immutable, so a
 * reader either sees the old one or the new one and never a torn state.
 */
public final class DeviceRegistry {

    private final ConcurrentMap<String, DeviceRecord> devices = new ConcurrentHashMap<>();

    public void recordTelemetry(Telemetry telemetry, long receivedAtMillis) {
        devices.compute(telemetry.deviceId(), (id, existing) ->
                (existing == null ? DeviceRecord.unknown(id) : existing)
                        .withTelemetry(telemetry, receivedAtMillis));
    }

    public void recordRejection(String deviceId) {
        devices.compute(deviceId, (id, existing) ->
                (existing == null ? DeviceRecord.unknown(id) : existing).withRejection());
    }

    public void recordPresence(String deviceId, Presence presence, long atMillis) {
        devices.compute(deviceId, (id, existing) ->
                (existing == null ? DeviceRecord.unknown(id) : existing)
                        .withPresence(presence, atMillis));
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

    /** Devices the gateway has actually received a reading from. */
    public long reportingCount() {
        return devices.values().stream().filter(record -> !record.presenceOnly()).count();
    }

    /** Devices the broker last reported as connected. */
    public long onlineCount() {
        return devices.values().stream()
                .filter(record -> record.presence() == Presence.ONLINE)
                .count();
    }
}
