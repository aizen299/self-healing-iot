package io.fleet.gateway;

import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;

import java.util.Optional;

/**
 * A change in the gateway's judgement of a device.
 *
 * <p>Carries {@code offlineSinceMillis} so the time a device spent failed can
 * be computed at the moment it recovers. That figure is the raw material for
 * MTTR — Pillar B's headline metric — and recovering it after the fact from
 * logs would be both awkward and unreliable.
 *
 * @param deviceId           the device whose health changed
 * @param from               previous health
 * @param to                 new health
 * @param atMillis           when the gateway made the call
 * @param missedHeartbeats   intervals of silence at that moment
 * @param offlineSinceMillis when this device was declared failed, or 0
 */
public record HealthTransition(
        String deviceId,
        DeviceHealth from,
        DeviceHealth to,
        long atMillis,
        int missedHeartbeats,
        long offlineSinceMillis) {

    /** A device has just been declared failed. */
    public boolean isFailure() {
        return to == DeviceHealth.OFFLINE;
    }

    /** A failed device has just been confirmed back in service. */
    public boolean isRecovery() {
        // OFFLINE directly to ONLINE happens when recoveryConfirmations is 1,
        // so probation is skipped; that is still a recovery and must still be
        // counted and timed.
        return to == DeviceHealth.ONLINE
                && (from == DeviceHealth.RECOVERING || from == DeviceHealth.OFFLINE);
    }

    /**
     * The event this transition should be announced and recorded as, if any.
     *
     * <p>One definition, used by both the publisher and the store. Two copies
     * of this switch meant the live event stream and the stored history could
     * come to disagree about which transitions matter, and the divergence
     * would only surface when someone reconciled a recovery against the record
     * of why it happened.
     *
     * <p>Empty for SUSPECTED: that is the detector hedging against a lost
     * QoS 0 message, and announcing it would invite consumers to act on what
     * is explicitly not yet a failure.
     */
    public Optional<DeviceEventType> eventType() {
        return switch (to) {
            case OFFLINE -> Optional.of(DeviceEventType.DEVICE_OFFLINE);
            case RECOVERING -> Optional.of(DeviceEventType.DEVICE_RECOVERING);
            case ONLINE -> isRecovery()
                    ? Optional.of(DeviceEventType.DEVICE_RECOVERED) : Optional.empty();
            case SUSPECTED, UNKNOWN -> Optional.empty();
        };
    }

    /**
     * Time from failure to confirmed recovery, or -1 when not applicable.
     *
     * <p>This is measured detection-to-confirmation, not fault-to-recovery:
     * the gateway cannot know when the device actually broke, only when it
     * concluded so. Any MTTR reported from this must say which it is.
     */
    public long recoveryDurationMillis() {
        if (!isRecovery() || offlineSinceMillis <= 0L) {
            return -1L;
        }
        return atMillis - offlineSinceMillis;
    }
}
