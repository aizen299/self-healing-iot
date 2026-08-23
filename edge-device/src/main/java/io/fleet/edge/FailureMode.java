package io.fleet.edge;

/**
 * Deterministic failure injection, triggered after a configured number of
 * readings so experiments reproduce exactly.
 *
 * <p>Every mode the design calls for is now implemented.
 */
public enum FailureMode {

    /** Device runs normally for the whole run. */
    NONE,

    /** Device dies abruptly, mid-run, without cleanup. */
    CRASH,

    /** Device starts publishing at a multiple of its configured rate. */
    MESSAGE_FLOOD,

    /**
     * Device loses its network link for a configured period, then recovers.
     *
     * <p>Handled in the MQTT sink rather than by {@link FailureInjector},
     * because the fault is in the transport, not in the device's logic: the
     * connection is dropped without a DISCONNECT so the broker fires the
     * device's Last Will, exactly as a severed link would. Requires the MQTT
     * sink — a network fault with no network is not a scenario.
     *
     * <p>Fires <strong>once per device per run</strong>, not periodically:
     * this models a single deterministic outage that the device then recovers
     * from, so a long run does not keep interrupting itself.
     */
    NETWORK_INTERRUPTION,

    /**
     * Device stops sending heartbeats but keeps its connection and keeps
     * publishing telemetry — a wedged liveness path rather than a dead device.
     *
     * <p>This is the fault that justifies heartbeat monitoring existing at
     * all. The connection stays up, so the broker never fires the Last Will;
     * the only thing that notices is the gateway timing out a device that has
     * gone quiet. Leaving telemetry flowing isolates the heartbeat path from
     * the other two detection routes, so a test of it cannot pass by accident.
     */
    HEARTBEAT_STOP;

    public static FailureMode parse(String raw) {
        return FailureMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
