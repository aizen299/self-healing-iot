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
        return elapsed <= 0L ? 0 : (int) (elapsed / heartbeatIntervalMillis);
    }

    /** State after a heartbeat arrives. */
    public DeviceHealth afterHeartbeat(DeviceHealth current, int consecutiveHeartbeats) {
        return switch (current) {
            // A device that was merely late was never actually broken, so it
            // returns to service immediately rather than serving probation.
            case UNKNOWN, ONLINE, SUSPECTED -> DeviceHealth.ONLINE;
            case OFFLINE -> DeviceHealth.RECOVERING;
            case RECOVERING -> consecutiveHeartbeats >= recoveryConfirmations
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

    /** How long a device may be silent before it is declared failed. */
    public long offlineThresholdMillis() {
        return heartbeatIntervalMillis * offlineAfterMisses;
    }
}
