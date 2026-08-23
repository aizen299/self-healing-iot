package io.fleet.common;

/**
 * Base of the project's checked exception hierarchy.
 *
 * <p>Checked deliberately. A recurring failure mode in messaging systems is
 * an ignored publish or parse error, which turns a broken pipeline into a
 * silently empty one. Forcing callers to acknowledge these keeps that from
 * happening by accident.
 */
public abstract class FleetException extends Exception {

    private static final long serialVersionUID = 1L;

    protected FleetException(String message) {
        super(message);
    }

    protected FleetException(String message, Throwable cause) {
        super(message, cause);
    }
}
