package io.fleet.edge;

/**
 * Deterministic failure injection, triggered after a configured number of
 * readings so experiments reproduce exactly.
 *
 * <p>Only the modes that are meaningful in Phase 1 exist here. There is no
 * network yet and no heartbeat yet, so {@code NETWORK_INTERRUPTION} arrives
 * with MQTT in Phase 2 and {@code HEARTBEAT_STOP} with heartbeat monitoring
 * in Phase 4. Declaring them now as unhandled constants would be a
 * placeholder in core functionality, which this project does not allow.
 */
public enum FailureMode {

    /** Device runs normally for the whole run. */
    NONE,

    /** Device dies abruptly, mid-run, without cleanup. */
    CRASH,

    /** Device starts publishing at a multiple of its configured rate. */
    MESSAGE_FLOOD;

    public static FailureMode parse(String raw) {
        return FailureMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
