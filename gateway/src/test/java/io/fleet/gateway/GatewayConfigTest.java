package io.fleet.gateway;

import io.fleet.common.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayConfigTest {

    @Test
    void appliesLocalDefaults() {
        GatewayConfig config = GatewayConfig.from(Map.of());

        assertEquals("tcp://127.0.0.1:1883", config.brokerUrl());
        assertEquals("fleet-gateway", config.clientId());
        assertEquals(1, config.subscriptionQos(),
                "presence matters, so the gateway subscribes at QoS 1");
        assertEquals(8080, config.httpPort());
        assertEquals(0L, config.runDurationSeconds(), "0 means run until interrupted");
    }

    @Test
    void readsOverridesFromTheEnvironment() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "MQTT_BROKER_URL", "tcp://broker:1884",
                "GATEWAY_CLIENT_ID", "gw-2",
                "GATEWAY_HTTP_PORT", "9999",
                "GATEWAY_RUN_DURATION_SECONDS", "30"));

        assertEquals("tcp://broker:1884", config.brokerUrl());
        assertEquals("gw-2", config.clientId());
        assertEquals(9999, config.httpPort());
        assertEquals(30L, config.runDurationSeconds());
    }

    @Test
    void rejectsABrokerUrlWithNoPort() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> GatewayConfig.from(Map.of("MQTT_BROKER_URL", "tcp://broker.local")));
        assertTrue(error.getMessage().contains("port"), error.getMessage());
    }

    @Test
    void rejectsAnUnsupportedScheme() {
        assertThrows(ConfigurationException.class,
                () -> GatewayConfig.from(Map.of("MQTT_BROKER_URL", "http://127.0.0.1:1883")));
    }

    @Test
    void rejectsAnOutOfRangePortOrQos() {
        assertThrows(ConfigurationException.class,
                () -> GatewayConfig.from(Map.of("GATEWAY_HTTP_PORT", "70000")));
        assertThrows(ConfigurationException.class,
                () -> GatewayConfig.from(Map.of("GATEWAY_SUBSCRIPTION_QOS", "3")));
    }

    @Test
    void allowsPortZeroToRequestAFreePort() {
        assertEquals(0, GatewayConfig.from(Map.of("GATEWAY_HTTP_PORT", "0")).httpPort());
    }
}
