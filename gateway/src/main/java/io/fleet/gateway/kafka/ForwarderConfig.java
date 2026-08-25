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
 * @param queueCapacity    records buffered before the caller's thread starts
 *                         dropping rather than waiting on Kafka
 */
public record ForwarderConfig(
        boolean enabled,
        String bootstrapServers,
        String clientId,
        int lingerMs,
        int requestTimeoutMs,
        int queueCapacity) {

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
        if (queueCapacity < 1) {
            throw new ConfigurationException(
                    "GATEWAY_KAFKA_QUEUE_CAPACITY must be >= 1, got " + queueCapacity);
        }
        // Kafka's own rule: delivery.timeout.ms must be at least
        // linger.ms + request.timeout.ms. The forwarder derives delivery
        // timeout from the request timeout alone, so a large linger with a
        // small request timeout produces a config the KafkaProducer
        // constructor rejects.
        //
        // Caught here rather than left to that constructor, because the
        // forwarder now retries a failed construction for ever instead of
        // falling back to the null object: a setting that can never work would
        // otherwise produce a line every ten seconds and no forwarding, which
        // is louder than the old silence but no more correct. Retrying cannot
        // tell "not up yet" from "never going to work"; this check can.
        long deliveryTimeout = Math.max(requestTimeoutMs * 2L, requestTimeoutMs + 1L);
        if (deliveryTimeout < (long) lingerMs + requestTimeoutMs) {
            throw new ConfigurationException(
                    "KAFKA_LINGER_MS (" + lingerMs + ") is too large for"
                            + " KAFKA_REQUEST_TIMEOUT_MS (" + requestTimeoutMs
                            + "); Kafka requires delivery.timeout.ms >= linger + request timeout");
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
                Env.intValue(env, "KAFKA_REQUEST_TIMEOUT_MS", 10_000),
                // Roughly ten seconds of a 50-device fleet at one reading per
                // second. Large enough to ride out a broker blip, small enough
                // that a sustained outage drops records rather than growing
                // without bound inside a memory-limited container.
                Env.intValue(env, "GATEWAY_KAFKA_QUEUE_CAPACITY", 1_000));
    }
}
