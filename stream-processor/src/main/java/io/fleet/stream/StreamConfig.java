package io.fleet.stream;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.time.Duration;
import java.util.Map;

/**
 * Stream processor configuration.
 *
 * @param bootstrapServers Kafka bootstrap servers
 * @param applicationId    Streams application id; also names its internal topics
 * @param windowSeconds    aggregation window
 * @param graceSeconds     how late a record may arrive and still count
 */
public record StreamConfig(
        String bootstrapServers,
        String applicationId,
        long windowSeconds,
        long graceSeconds) {

    public StreamConfig {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new ConfigurationException("KAFKA_BOOTSTRAP_SERVERS must not be blank");
        }
        if (applicationId == null || applicationId.isBlank()) {
            throw new ConfigurationException("STREAM_APPLICATION_ID must not be blank");
        }
        if (windowSeconds < 1) {
            throw new ConfigurationException(
                    "STREAM_WINDOW_SECONDS must be >= 1, got " + windowSeconds);
        }
        if (graceSeconds < 0) {
            throw new ConfigurationException(
                    "STREAM_GRACE_SECONDS must be >= 0, got " + graceSeconds);
        }
    }

    public static StreamConfig fromEnv() {
        return from(System.getenv());
    }

    public static StreamConfig from(Map<String, String> env) {
        return new StreamConfig(
                Env.string(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                Env.string(env, "STREAM_APPLICATION_ID", "fleet-stream-processor"),
                Env.longValue(env, "STREAM_WINDOW_SECONDS", 10L),
                // Some grace by default. Devices publish at QoS 0 over a
                // network and the gateway forwards asynchronously, so a record
                // arriving a little late is ordinary rather than exceptional.
                Env.longValue(env, "STREAM_GRACE_SECONDS", 5L));
    }

    public Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    public Duration grace() {
        return Duration.ofSeconds(graceSeconds);
    }
}
