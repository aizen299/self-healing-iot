package io.fleet.common;

/**
 * Health transitions the gateway announces on {@code fleet/{deviceId}/events}.
 *
 * <p>Only the transitions something downstream must act on are published.
 * {@code ONLINE -> SUSPECTED} is deliberately not an event: suspicion is the
 * detector's internal hedging against a lost QoS 0 message, and publishing it
 * would invite consumers to react to what is explicitly not yet a failure.
 */
public enum DeviceEventType {

    /** A device has been declared failed. Phase 9's recovery reacts to this. */
    DEVICE_OFFLINE,

    /** A failed device is heartbeating again and is on probation. */
    DEVICE_RECOVERING,

    /** A device has returned to service; the failure is resolved. */
    DEVICE_RECOVERED
}
