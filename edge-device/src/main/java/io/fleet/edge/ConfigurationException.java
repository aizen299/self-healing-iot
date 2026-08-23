package io.fleet.edge;

/**
 * Signals invalid configuration.
 *
 * <p>Unchecked because configuration is validated once at startup and a bad
 * value is an operator error with no recovery path — the correct response is
 * to fail before any telemetry is produced, not to degrade quietly. An
 * experiment started with a misread config would otherwise record results
 * against parameters nobody intended.
 */
public final class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
