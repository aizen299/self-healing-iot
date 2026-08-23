package io.fleet.common;

/**
 * The gateway's judgement of a device, derived from heartbeat timing.
 *
 * <p>Distinct from both {@link DeviceStatus} (what the device says about its
 * own sensors) and {@link Presence} (whether the broker has a connection).
 * This is the only one of the three that represents an inference rather than
 * a report, and it is the one recovery acts on.
 *
 * <pre>
 * UNKNOWN ──first heartbeat──▶ ONLINE
 *                               │  ▲                    ▲
 *              missed ≥ suspect │  │ heartbeat          │ confirmations met
 *                               ▼  │                    │
 *                            SUSPECTED                  │
 *                               │                       │
 *              missed ≥ offline │                  RECOVERING
 *                               ▼                       ▲
 *                            OFFLINE ──heartbeat again──┘
 * </pre>
 *
 * <p>{@code SUSPECTED} exists so a single missed heartbeat cannot condemn a
 * device. Telemetry and heartbeats travel at QoS 0 (ADR-004), so an
 * occasional loss is expected rather than exceptional, and a detector that
 * reacted to one would spend its time recovering healthy devices.
 *
 * <p>{@code RECOVERING} is a probation state, not a synonym for "being
 * recovered": a device that starts heartbeating again has to keep doing so
 * before it is trusted. A replacement workload provisioned in Phase 9 enters
 * the fleet through exactly this path, so the same rule covers both a device
 * that healed itself and one that was replaced.
 */
public enum DeviceHealth {

    /** No heartbeat has ever been received. */
    UNKNOWN,

    /** Heartbeats are arriving on time. */
    ONLINE,

    /** Heartbeats are late, but not yet late enough to call it a failure. */
    SUSPECTED,

    /** Declared failed. This is what recovery reacts to. */
    OFFLINE,

    /** Heartbeats resumed after a failure; awaiting confirmation. */
    RECOVERING;

    /** Whether this state means the gateway considers the device usable. */
    public boolean isHealthy() {
        return this == ONLINE;
    }

    /** Whether a failure has been declared and not yet resolved. */
    public boolean isFailed() {
        return this == OFFLINE;
    }
}
