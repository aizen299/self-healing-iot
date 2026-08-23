package io.fleet.common;

import java.nio.charset.StandardCharsets;

/**
 * Connection-level presence, published retained on {@code fleet/{id}/status}.
 *
 * <p>Distinct from {@link DeviceStatus}, which is the device's opinion of its
 * own sensors. Presence is the broker's account of whether the device is
 * connected at all: {@code ONLINE} is published on connect, and {@code OFFLINE}
 * is registered as the connection's Last Will so the broker announces it even
 * when the device dies without a chance to say anything.
 *
 * <p>Retained, so a subscriber that joins late still learns the current state
 * of every device rather than waiting for the next transition.
 */
public enum Presence {

    /** Connected. Published by the device on connect. */
    ONLINE,

    /**
     * Gone without saying goodbye — published by the <em>broker</em> as the
     * device's Last Will.
     *
     * <p>This is a failure signal. It means the connection dropped without a
     * DISCONNECT: power loss, a kill, a severed network. The gateway declares
     * the device failed on receipt rather than waiting for a heartbeat
     * timeout.
     */
    OFFLINE,

    /**
     * Left deliberately — published by the device itself immediately before a
     * clean DISCONNECT.
     *
     * <p>Distinct from {@link #OFFLINE} because the two are otherwise
     * indistinguishable to a subscriber, and conflating them would make every
     * orderly fleet shutdown look like a fleet-wide failure — which from
     * Phase 9 would mean provisioning replacements for devices that were
     * stopped on purpose.
     */
    SHUTDOWN;

    private final byte[] payload = name().getBytes(StandardCharsets.UTF_8);

    /** The wire payload. Cloned per call so callers cannot mutate shared state. */
    public byte[] payload() {
        return payload.clone();
    }
}
