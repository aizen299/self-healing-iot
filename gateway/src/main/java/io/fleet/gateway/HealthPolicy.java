package io.fleet.gateway;

import io.fleet.common.ConfigurationException;
import io.fleet.common.DeviceHealth;

/**
 * The rules that turn heartbeat timing into a health judgement.
 *
 * <p>Deliberately a pure function of (current state, missed heartbeats,
 * consecutive heartbeats). Detection is the part of this system whose
 * correctness matters most and whose behaviour is hardest to observe in a
 * running fleet, so the decision is kept somewhere a test can drive every
 * transition directly rather than by waiting on real time.
 *
 * @param heartbeatIntervalMillis how often a healthy device is expected to
 *                                report; must match the fleet's publish
 *                                interval, since heartbeats ride that tick
 * @param suspectAfterMisses      misses before a device becomes suspect
 * @param offlineAfterMisses      misses before a device is declared failed
 * @param recoveryConfirmations   consecutive heartbeats a recovering device
 *                                must deliver before it is trusted again
 */
public record HealthPolicy(
        long heartbeatIntervalMillis,
        int suspectAfterMisses,
        int offlineAfterMisses,
        int recoveryConfirmations) {

    public HealthPolicy {
        if (heartbeatIntervalMillis < 1) {
            throw new ConfigurationException(
                    "GATEWAY_HEARTBEAT_INTERVAL_MS must be >= 1, got " + heartbeatIntervalMillis);
        }
        // Two, not one. Heartbeats travel at QoS 0 (ADR-004), so a single loss
        // is expected traffic rather than evidence of failure, and a detector
        // that condemned on one miss would spend the run recovering healthy
        // devices. This is the "consecutive missed heartbeats" rule the design
        // calls for, expressed as a threshold.
        if (suspectAfterMisses < 2) {
            throw new ConfigurationException(
                    "GATEWAY_SUSPECT_AFTER_MISSES must be >= 2 so a single lost heartbeat"
                            + " cannot condemn a device, got " + suspectAfterMisses);
        }
        if (offlineAfterMisses <= suspectAfterMisses) {
            throw new ConfigurationException(
                    "GATEWAY_OFFLINE_AFTER_MISSES (" + offlineAfterMisses + ") must exceed"
                            + " GATEWAY_SUSPECT_AFTER_MISSES (" + suspectAfterMisses
                            + "), or SUSPECTED could never be observed");
        }
        if (recoveryConfirmations < 1) {
            throw new ConfigurationException(
                    "GATEWAY_RECOVERY_CONFIRMATIONS must be >= 1, got " + recoveryConfirmations);
        }
    }

    /**
     * Whole heartbeat intervals elapsed since the last one arrived.
     *
     * <p>Measured against the gateway's receipt time, never the device's own
     * clock: a wedged device may be wrong about the time, and a device whose
     * clock jumps forward must not be able to make itself look alive.
     */
    public int missedHeartbeats(long lastHeartbeatAtMillis, long nowMillis) {
        if (lastHeartbeatAtMillis <= 0L) {
            return 0;
        }
        long elapsed = nowMillis - lastHeartbeatAtMillis;
        if (elapsed <= 0L) {
            return 0;
        }
        // Clamped rather than cast: a long silence against a small interval
        // overflows int and goes negative, which fails every >= below and
        // would leave the longest-silent device the one never declared failed.
        return (int) Math.min(Integer.MAX_VALUE, elapsed / heartbeatIntervalMillis);
    }

    /** State after a heartbeat arrives. */
    public DeviceHealth afterHeartbeat(DeviceHealth current, int consecutiveHeartbeats) {
        return switch (current) {
            // A device that was merely late was never actually broken, so it
            // returns to service immediately rather than serving probation.
            case UNKNOWN, ONLINE, SUSPECTED -> DeviceHealth.ONLINE;
            // The threshold is checked here too, so recoveryConfirmations=1
            // means one heartbeat restores service. Without it, 1 and 2 both
            // required two heartbeats and the value silently did nothing.
            case OFFLINE, RECOVERING -> consecutiveHeartbeats >= recoveryConfirmations
                    ? DeviceHealth.ONLINE : DeviceHealth.RECOVERING;
        };
    }

    /** State after {@code missed} intervals of silence. */
    public DeviceHealth afterSilence(DeviceHealth current, int missed) {
        return switch (current) {
            // Never heard from at all. Silence from a device that has not yet
            // introduced itself is not a failure to recover from — it may
            // simply not exist, which is exactly the retained-presence ghost
            // case ADR-004 warns about.
            case UNKNOWN -> DeviceHealth.UNKNOWN;
            case OFFLINE -> DeviceHealth.OFFLINE;
            case ONLINE, SUSPECTED, RECOVERING -> {
                if (missed >= offlineAfterMisses) {
                    yield DeviceHealth.OFFLINE;
                }
                if (current == DeviceHealth.RECOVERING) {
                    yield DeviceHealth.RECOVERING;
                }
                yield missed >= suspectAfterMisses ? DeviceHealth.SUSPECTED : current;
            }
        };
    }

    /**
     * State after the broker reports a connection change.
     *
     * <p>The fast detection path. A fired Last Will is proof the device is
     * gone, so there is nothing to wait for — but a device the gateway has
     * never heard from stays {@code UNKNOWN}, which is what keeps a retained
     * OFFLINE from an earlier run (ADR-004) out of the failure path.
     *
     * <p>{@code SHUTDOWN} returns the device to {@code UNKNOWN}: it left on
     * purpose, so it is no longer a liveness candidate and must not be
     * declared failed by a later sweep.
     */
    public DeviceHealth afterPresence(DeviceHealth current, io.fleet.common.Presence presence) {
        return switch (presence) {
            case OFFLINE -> current == DeviceHealth.UNKNOWN ? DeviceHealth.UNKNOWN
                    : DeviceHealth.OFFLINE;
            case SHUTDOWN -> DeviceHealth.UNKNOWN;
            // Connecting is not proof of liveness; only a heartbeat is.
            case ONLINE -> current;
        };
    }

    /** How long a device may be silent before it is declared failed. */
    public long offlineThresholdMillis() {
        return heartbeatIntervalMillis * offlineAfterMisses;
    }
}
