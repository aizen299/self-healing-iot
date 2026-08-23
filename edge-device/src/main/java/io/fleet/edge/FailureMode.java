package io.fleet.edge;

/**
 * Deterministic failure injection, triggered after a configured number of
 * readings so experiments reproduce exactly.
 *
 * <p>Only the modes that are meaningful today exist here. There is still no
 * heartbeat, so {@code HEARTBEAT_STOP} arrives with heartbeat monitoring in
 * Phase 4. Declaring it now as an unhandled constant would be a placeholder
 * in core functionality, which this project does not allow.
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
     */
    NETWORK_INTERRUPTION;

    public static FailureMode parse(String raw) {
        return FailureMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
