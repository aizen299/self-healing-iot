package io.fleet.common;

/**
 * Thrown when telemetry or an event cannot be persisted or queried.
 *
 * <p>Checked, like the rest of the hierarchy: a storage failure that is
 * swallowed turns into a history with silent gaps, and a gap is invisible
 * precisely when it matters — an experiment reads as though the fleet was
 * quiet rather than as though the recorder was broken.
 */
public class StoreException extends FleetException {

    private static final long serialVersionUID = 1L;

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
