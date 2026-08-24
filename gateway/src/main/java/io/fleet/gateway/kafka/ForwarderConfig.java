package io.fleet.gateway.kafka;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.util.Map;

/**
 * Kafka forwarding configuration.
 *
 * <p>Off by default. Kafka is the heaviest service in the stack by a wide
 * margin, and the gateway ingests, detects, and persists perfectly well
 * without it — so needing a broker to run the gateway would be a step
 * backwards from Phase 5.
 *
 * @param enabled          whether anything is forwarded at all
 * @param bootstrapServers Kafka bootstrap servers
 * @param clientId         producer client id
 * @param lingerMs         how long the producer batches before sending
 * @param requestTimeoutMs ceiling on a produce request
 */
public record ForwarderConfig(
        boolean enabled,
        String bootstrapServers,
        String clientId,
        int lingerMs,
        int requestTimeoutMs) {

    public ForwarderConfig {
        if (enabled && (bootstrapServers == null || bootstrapServers.isBlank())) {
            throw new ConfigurationException(
                    "KAFKA_BOOTSTRAP_SERVERS must be set when GATEWAY_KAFKA_ENABLED is true");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new ConfigurationException("GATEWAY_KAFKA_CLIENT_ID must not be blank");
        }
        if (lingerMs < 0) {
            throw new ConfigurationException("KAFKA_LINGER_MS must be >= 0, got " + lingerMs);
        }
        if (requestTimeoutMs < 1) {
            throw new ConfigurationException(
                    "KAFKA_REQUEST_TIMEOUT_MS must be >= 1, got " + requestTimeoutMs);
        }
    }

    public static ForwarderConfig fromEnv() {
        return from(System.getenv());
    }

    public static ForwarderConfig from(Map<String, String> env) {
        return new ForwarderConfig(
                Env.booleanValue(env, "GATEWAY_KAFKA_ENABLED", false),
                Env.string(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                Env.string(env, "GATEWAY_KAFKA_CLIENT_ID", "fleet-gateway"),
                // A little batching. Telemetry is high volume and individually
                // disposable, so a few milliseconds of linger buys a large
                // reduction in requests; failures are rare enough that the
                // same delay costs nothing noticeable.
                Env.intValue(env, "KAFKA_LINGER_MS", 20),
                Env.intValue(env, "KAFKA_REQUEST_TIMEOUT_MS", 10_000));
    }
}
