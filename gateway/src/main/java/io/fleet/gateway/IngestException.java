package io.fleet.gateway;

import io.fleet.common.FleetException;

/**
 * Thrown when the gateway cannot establish or release its broker connection.
 *
 * <p>Wraps the client library's own exception so the transport stays an
 * implementation detail. ADR-004 leaves an MQTT 5 upgrade open, and callers
 * should not have to change when that happens.
 */
public final class IngestException extends FleetException {

    private static final long serialVersionUID = 1L;

    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
