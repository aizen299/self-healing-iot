package io.fleet.recovery;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.util.Map;

/**
 * Operator configuration, read from the environment.
 *
 * <p>Nothing is hardcoded at a call site, for the same reason as everywhere
 * else in this project: a run is fully described by its environment block, so
 * an experiment can be replayed from the record of it.
 *
 * @param bootstrapServers   Kafka, where failures arrive and recoveries are announced
 * @param consumerGroupId    the group; also this operator's mutual exclusion, see below
 * @param namespace          namespace the device pods live in
 * @param deviceAppLabel     value of the {@code app} label device pods carry
 * @param deviceIdPrefix     prefix device ids are built from; must match the fleet's
 * @param apiTimeoutSeconds  ceiling on any single Kubernetes call
 * @param pollTimeoutMillis  how long a consumer poll waits for records
 * @param replaceLiveDevices replace a device whose pod is still running
 * @param runDurationSeconds stop after this long; 0 runs until interrupted
 */
public record OperatorConfig(
        String bootstrapServers,
        String consumerGroupId,
        String namespace,
        String deviceAppLabel,
        String deviceIdPrefix,
        int apiTimeoutSeconds,
        long pollTimeoutMillis,
        boolean replaceLiveDevices,
        long runDurationSeconds,
        int metricsPort) {

    public OperatorConfig {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new ConfigurationException("KAFKA_BOOTSTRAP_SERVERS must not be blank");
        }
        if (consumerGroupId == null || consumerGroupId.isBlank()) {
            throw new ConfigurationException("OPERATOR_GROUP_ID must not be blank");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new ConfigurationException("OPERATOR_NAMESPACE must not be blank");
        }
        if (deviceIdPrefix == null || deviceIdPrefix.isBlank()) {
            throw new ConfigurationException("FLEET_DEVICE_ID_PREFIX must not be blank");
        }
        if (apiTimeoutSeconds < 1) {
            throw new ConfigurationException(
                    "OPERATOR_API_TIMEOUT_SECONDS must be >= 1, got " + apiTimeoutSeconds);
        }
        if (pollTimeoutMillis < 1) {
            throw new ConfigurationException(
                    "OPERATOR_POLL_TIMEOUT_MS must be >= 1, got " + pollTimeoutMillis);
        }
        if (runDurationSeconds < 0) {
            throw new ConfigurationException(
                    "OPERATOR_RUN_DURATION_SECONDS must be >= 0, got " + runDurationSeconds);
        }
        // 0 is allowed and means an ephemeral port, which is how a test binds
        // without racing another one for a fixed number.
        if (metricsPort < 0 || metricsPort > 65_535) {
            throw new ConfigurationException(
                    "OPERATOR_METRICS_PORT must be between 0 and 65535, got " + metricsPort);
        }
    }

    public static OperatorConfig fromEnv() {
        return from(System.getenv());
    }

    public static OperatorConfig from(Map<String, String> env) {
        return new OperatorConfig(
                Env.string(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                // One group, so one consumer holds device.failures at a time.
                // With a single partition that is the operator's mutual
                // exclusion: two replicas cannot both act on a failure,
                // because only one of them is assigned the partition. See
                // ADR-011 — it is why there is no leader election here.
                Env.string(env, "OPERATOR_GROUP_ID", "fleet-recovery-operator"),
                Env.string(env, "OPERATOR_NAMESPACE", "fleet"),
                Env.string(env, "OPERATOR_DEVICE_APP_LABEL", "edge-device"),
                // Must match the fleet's FLEET_DEVICE_ID_PREFIX: the operator
                // reads an index back out of a device id to decide which slice
                // of the fleet a replacement runs.
                Env.string(env, "FLEET_DEVICE_ID_PREFIX", "device"),
                Env.intValue(env, "OPERATOR_API_TIMEOUT_SECONDS", 10),
                Env.longValue(env, "OPERATOR_POLL_TIMEOUT_MS", 1_000L),
                // Off. A device whose pod is still running has not failed in
                // any way replacing it would fix, and killing it would turn a
                // false positive into a real outage. On only for the wedged
                // case, where the process lives and the heartbeat has stopped.
                Env.booleanValue(env, "OPERATOR_REPLACE_LIVE_DEVICES", false),
                Env.longValue(env, "OPERATOR_RUN_DURATION_SECONDS", 0L),
                // The same port the gateway serves on, because it is a
                // different pod. One number to remember rather than two.
                Env.intValue(env, "OPERATOR_METRICS_PORT", 8080));
    }
}
