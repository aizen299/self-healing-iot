package io.fleet.edge.mqtt;

import io.fleet.common.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttConfigTest {

    @Test
    void appliesLocalDefaults() {
        MqttConfig config = MqttConfig.from(Map.of());

        assertEquals("tcp://127.0.0.1:1883", config.brokerUrl());
        assertEquals(0, config.qos(), "telemetry defaults to QoS 0 per ADR-004");
        assertTrue(config.cleanSession());
        assertTrue(config.publishRetainedStatus());
    }

    @Test
    void buildsAUniqueClientIdPerDevice() {
        MqttConfig config = MqttConfig.from(Map.of("MQTT_CLIENT_ID_PREFIX", "fleet"));

        assertEquals("fleet-device-001", config.clientId("device-001"));
        assertTrue(!config.clientId("device-001").equals(config.clientId("device-002")),
                "MQTT requires a distinct client id per connection");
    }

    @Test
    void rejectsAnUnsupportedBrokerScheme() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_BROKER_URL", "http://127.0.0.1:1883")));
        assertTrue(error.getMessage().contains("tcp://"), error.getMessage());
    }

    @Test
    void rejectsABrokerUrlWithNoPort() {
        // Left to Paho this surfaces as a generic connect failure, pointing at
        // the broker rather than at the variable that was mistyped.
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_BROKER_URL", "tcp://broker.local")));
        assertTrue(error.getMessage().contains("port"), error.getMessage());
    }

    @Test
    void rejectsAnOutOfRangeQos() {
        assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_QOS", "3")));
        assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_QOS", "-1")));
    }

    @Test
    void rejectsAnUnparseableBooleanRatherThanTreatingItAsFalse() {
        // Boolean.parseBoolean maps anything unrecognised to false, which would
        // silently disable retained presence an operator meant to keep on.
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_RETAINED_STATUS", "yes")));
        assertTrue(error.getMessage().contains("true or false"), error.getMessage());
    }

    @Test
    void rejectsNonPositiveTimings() {
        assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_KEEPALIVE_SECONDS", "0")));
        assertThrows(ConfigurationException.class,
                () -> MqttConfig.from(Map.of("MQTT_CONNECTION_TIMEOUT_SECONDS", "0")));
    }
}
