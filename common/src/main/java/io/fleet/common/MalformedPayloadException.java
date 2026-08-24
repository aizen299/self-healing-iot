package io.fleet.common;


/**
 * Thrown when a payload cannot be read as telemetry.
 *
 * <p>Separate from {@code ValidationException}, which means the reading
 * parsed but its values are impossible. The gateway counts the two apart
 * because they point at different faults: a malformed payload suggests a
 * producer speaking the wrong format, while an out-of-range value suggests a
 * sensor or a simulation problem.
 */
public final class MalformedPayloadException extends FleetException {

    private static final long serialVersionUID = 1L;

    public MalformedPayloadException(String message) {
        super(message);
    }

    public MalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
