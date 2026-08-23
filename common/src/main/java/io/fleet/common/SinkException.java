package io.fleet.common;

/** Thrown when a {@link TelemetrySink} cannot accept a payload. */
public final class SinkException extends FleetException {

    private static final long serialVersionUID = 1L;

    public SinkException(String message) {
        super(message);
    }

    public SinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
