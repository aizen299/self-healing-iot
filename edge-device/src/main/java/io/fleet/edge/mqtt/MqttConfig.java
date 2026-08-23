package io.fleet.edge.mqtt;

import io.fleet.edge.ConfigurationException;

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
    }

    public static MqttConfig fromEnv() {
        return from(System.getenv());
    }

    public static MqttConfig from(Map<String, String> env) {
        return new MqttConfig(
                value(env, "MQTT_BROKER_URL", "tcp://127.0.0.1:1883"),
                value(env, "MQTT_CLIENT_ID_PREFIX", "fleet"),
                parseInt(env, "MQTT_QOS", 0),
                parseInt(env, "MQTT_KEEPALIVE_SECONDS", 60),
                parseInt(env, "MQTT_CONNECTION_TIMEOUT_SECONDS", 10),
                parseBoolean(env, "MQTT_CLEAN_SESSION", true),
                parseBoolean(env, "MQTT_AUTOMATIC_RECONNECT", true),
                parseBoolean(env, "MQTT_RETAINED_STATUS", true));
    }

    /** Client id for one device; MQTT requires these to be unique per connection. */
    public String clientId(String deviceId) {
        return clientIdPrefix + "-" + deviceId;
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String raw = env.get(key);
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }

    private static int parseInt(Map<String, String> env, String key, int fallback) {
        String raw = value(env, key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer, got '" + raw + "'", e);
        }
    }

    private static boolean parseBoolean(Map<String, String> env, String key, boolean fallback) {
        String raw = value(env, key, null);
        if (raw == null) {
            return fallback;
        }
        if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(raw);
        }
        // Boolean.parseBoolean maps anything unrecognised to false, which would
        // silently disable a feature the operator meant to enable.
        throw new ConfigurationException(key + " must be true or false, got '" + raw + "'");
    }
}
