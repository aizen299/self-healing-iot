package io.fleet.edge.mqtt;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * MQTT connection settings, read from the environment like everything else so
 * a run stays reproducible from its environment block alone.
 *
 * @param brokerUrl                 e.g. {@code tcp://127.0.0.1:1883}
 * @param clientIdPrefix            prefixed to the device id to form the client id
 * @param qos                       quality of service for telemetry (see ADR-004)
 * @param keepAliveSeconds          MQTT keep-alive interval
 * @param connectionTimeoutSeconds  how long a connect attempt may take
 * @param operationTimeoutSeconds   ceiling on any blocking client call
 * @param cleanSession              whether the broker discards session state on disconnect
 * @param automaticReconnect        whether Paho reconnects after an unexpected drop
 * @param publishRetainedStatus     whether to publish retained ONLINE/OFFLINE presence
 */
public record MqttConfig(
        String brokerUrl,
        String clientIdPrefix,
        int qos,
        int keepAliveSeconds,
        int connectionTimeoutSeconds,
        int operationTimeoutSeconds,
        boolean cleanSession,
        boolean automaticReconnect,
        boolean publishRetainedStatus) {

    public MqttConfig {
        if (brokerUrl == null || brokerUrl.isBlank()) {
            throw new ConfigurationException("MQTT_BROKER_URL must not be blank");
        }
        if (!brokerUrl.startsWith("tcp://") && !brokerUrl.startsWith("ssl://")) {
            throw new ConfigurationException(
                    "MQTT_BROKER_URL must start with tcp:// or ssl://, got '" + brokerUrl + "'");
        }
        // Checked here rather than left to Paho: a missing port surfaces from
        // the client as a generic connect failure, which points the operator
        // at the broker instead of at the variable they mistyped.
        URI parsed;
        try {
            parsed = new URI(brokerUrl);
        } catch (URISyntaxException e) {
            throw new ConfigurationException(
                    "MQTT_BROKER_URL is not a valid URI: '" + brokerUrl + "'", e);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new ConfigurationException(
                    "MQTT_BROKER_URL has no host: '" + brokerUrl + "'");
        }
        if (parsed.getPort() < 1) {
            throw new ConfigurationException(
                    "MQTT_BROKER_URL must include a port, e.g. tcp://host:1883, got '"
                            + brokerUrl + "'");
        }
        if (clientIdPrefix == null || clientIdPrefix.isBlank()) {
            throw new ConfigurationException("MQTT_CLIENT_ID_PREFIX must not be blank");
        }
        if (qos < 0 || qos > 2) {
            throw new ConfigurationException("MQTT_QOS must be 0, 1, or 2, got " + qos);
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
    }

    public static MqttConfig fromEnv() {
        return from(System.getenv());
    }

    public static MqttConfig from(Map<String, String> env) {
        return new MqttConfig(
                Env.string(env, "MQTT_BROKER_URL", "tcp://127.0.0.1:1883"),
                Env.string(env, "MQTT_CLIENT_ID_PREFIX", "fleet"),
                Env.intValue(env, "MQTT_QOS", 0),
                Env.intValue(env, "MQTT_KEEPALIVE_SECONDS", 60),
                Env.intValue(env, "MQTT_CONNECTION_TIMEOUT_SECONDS", 10),
                Env.intValue(env, "MQTT_OPERATION_TIMEOUT_SECONDS", 10),
                Env.booleanValue(env, "MQTT_CLEAN_SESSION", true),
                Env.booleanValue(env, "MQTT_AUTOMATIC_RECONNECT", true),
                Env.booleanValue(env, "MQTT_RETAINED_STATUS", true));
    }

    /** Client id for one device; MQTT requires these to be unique per connection. */
    public String clientId(String deviceId) {
        return clientIdPrefix + "-" + deviceId;
    }
}
