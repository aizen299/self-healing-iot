package io.fleet.common;

/** Thrown when a telemetry reading fails validation. */
public final class ValidationException extends FleetException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
