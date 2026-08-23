package io.fleet.gateway;

import io.fleet.common.BrokerUrl;
import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.util.Map;

/**
 * Gateway configuration, read from the environment.
 *
 * @param brokerUrl                MQTT broker, e.g. {@code tcp://127.0.0.1:1883}
 * @param clientId                 MQTT client id; must be unique on the broker
 * @param subscriptionQos          QoS requested when subscribing
 * @param keepAliveSeconds         MQTT keep-alive interval
 * @param connectionTimeoutSeconds ceiling on a connect attempt
 * @param operationTimeoutSeconds  ceiling on any blocking client call
 * @param cleanSession             whether the broker discards session state
 * @param httpHost                 bind address for the health API
 * @param httpPort                 port for the health API; 0 picks a free one
 * @param runDurationSeconds       stop after this long; 0 means run until interrupted
 * @param heartbeatIntervalMillis  expected heartbeat period; must match the fleet's
 *                                 publish interval, since heartbeats ride that tick
 * @param suspectAfterMisses       misses before a device becomes suspect
 * @param offlineAfterMisses       misses before a device is declared failed
 * @param recoveryConfirmations    heartbeats a recovering device must deliver
 * @param monitorIntervalMillis    how often the silence sweep runs
 */
public record GatewayConfig(
        String brokerUrl,
        String clientId,
        int subscriptionQos,
        int keepAliveSeconds,
        int connectionTimeoutSeconds,
        int operationTimeoutSeconds,
        boolean cleanSession,
        String httpHost,
        int httpPort,
        long runDurationSeconds,
        long heartbeatIntervalMillis,
        int suspectAfterMisses,
        int offlineAfterMisses,
        int recoveryConfirmations,
        long monitorIntervalMillis) {

    public GatewayConfig {
        BrokerUrl.validate("MQTT_BROKER_URL", brokerUrl);
        if (clientId == null || clientId.isBlank()) {
            throw new ConfigurationException("GATEWAY_CLIENT_ID must not be blank");
        }
        if (subscriptionQos < 0 || subscriptionQos > 2) {
            throw new ConfigurationException(
                    "GATEWAY_SUBSCRIPTION_QOS must be 0, 1, or 2, got " + subscriptionQos);
        }
        if (keepAliveSeconds < 1) {
            throw new ConfigurationException(
                    "MQTT_KEEPALIVE_SECONDS must be >= 1, got " + keepAliveSeconds);
        }
        if (connectionTimeoutSeconds < 1) {
            throw new ConfigurationException(
                    "MQTT_CONNECTION_TIMEOUT_SECONDS must be >= 1, got " + connectionTimeoutSeconds);
        }
        if (operationTimeoutSeconds < 1) {
            throw new ConfigurationException(
                    "MQTT_OPERATION_TIMEOUT_SECONDS must be >= 1, got " + operationTimeoutSeconds);
        }
        if (httpPort < 0 || httpPort > 65535) {
            throw new ConfigurationException(
                    "GATEWAY_HTTP_PORT must be 0-65535 (0 picks a free port), got " + httpPort);
        }
        if (runDurationSeconds < 0) {
            throw new ConfigurationException(
                    "GATEWAY_RUN_DURATION_SECONDS must be >= 0, got " + runDurationSeconds);
        }
        if (monitorIntervalMillis < 1) {
            throw new ConfigurationException(
                    "GATEWAY_MONITOR_INTERVAL_MS must be >= 1, got " + monitorIntervalMillis);
        }
        // Validates the detection thresholds by constructing the policy, so
        // the rules live in one place rather than being restated here.
        //
        // Built from the constructor parameters, not via healthPolicy(): in a
        // record's compact constructor the fields are not assigned until the
        // body completes, so an instance method called from here would read
        // zeros and validate nothing.
        new HealthPolicy(heartbeatIntervalMillis, suspectAfterMisses,
                offlineAfterMisses, recoveryConfirmations);
    }

    /** The detection rules implied by this configuration. */
    public HealthPolicy healthPolicy() {
        return new HealthPolicy(heartbeatIntervalMillis, suspectAfterMisses,
                offlineAfterMisses, recoveryConfirmations);
    }

    public static GatewayConfig fromEnv() {
        return from(System.getenv());
    }

    public static GatewayConfig from(Map<String, String> env) {
        return new GatewayConfig(
                Env.string(env, "MQTT_BROKER_URL", "tcp://127.0.0.1:1883"),
                Env.string(env, "GATEWAY_CLIENT_ID", "fleet-gateway"),
                Env.intValue(env, "GATEWAY_SUBSCRIPTION_QOS", 1),
                Env.intValue(env, "MQTT_KEEPALIVE_SECONDS", 60),
                Env.intValue(env, "MQTT_CONNECTION_TIMEOUT_SECONDS", 10),
                Env.intValue(env, "MQTT_OPERATION_TIMEOUT_SECONDS", 10),
                Env.booleanValue(env, "MQTT_CLEAN_SESSION", true),
                Env.string(env, "GATEWAY_HTTP_HOST", "127.0.0.1"),
                Env.intValue(env, "GATEWAY_HTTP_PORT", 8080),
                Env.longValue(env, "GATEWAY_RUN_DURATION_SECONDS", 0L),
                Env.longValue(env, "GATEWAY_HEARTBEAT_INTERVAL_MS", 1000L),
                Env.intValue(env, "GATEWAY_SUSPECT_AFTER_MISSES", 2),
                Env.intValue(env, "GATEWAY_OFFLINE_AFTER_MISSES", 4),
                Env.intValue(env, "GATEWAY_RECOVERY_CONFIRMATIONS", 2),
                Env.longValue(env, "GATEWAY_MONITOR_INTERVAL_MS", 250L));
    }
}
