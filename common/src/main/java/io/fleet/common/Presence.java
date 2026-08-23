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
    ONLINE,
    OFFLINE;

    private final byte[] payload = name().getBytes(StandardCharsets.UTF_8);

    /** The wire payload. Cloned per call so callers cannot mutate shared state. */
    public byte[] payload() {
        return payload.clone();
    }
}
