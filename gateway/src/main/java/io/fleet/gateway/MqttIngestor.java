package io.fleet.gateway;

import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryValidator;
import io.fleet.common.Topics;
import io.fleet.common.ValidationException;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Subscribes to the fleet's topics and feeds the registry.
 *
 * <p>Nothing thrown from a message callback may escape: Paho would log it and
 * carry on, and the gateway would appear healthy while quietly dropping every
 * message of that shape. Each failure is instead classified, counted, and
 * logged with the topic that caused it.
 */
public final class MqttIngestor implements MqttCallback, AutoCloseable {

    private final GatewayConfig config;
    private final DeviceRegistry registry;
    private final GatewayMetrics metrics;
    private final TelemetryParser parser = new TelemetryParser();
    private final Clock clock;
    private final MqttClient client;

    public MqttIngestor(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics) {
        this(config, registry, metrics, Clock.systemUTC());
    }

    public MqttIngestor(
            GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics, Clock clock) {
        this.config = config;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
        try {
            this.client = new MqttClient(
                    config.brokerUrl(), config.clientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("could not create the gateway MQTT client", e);
        }
    }

    /** Connects and subscribes to telemetry and presence for the whole fleet. */
    public void start() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(config.cleanSession());
        options.setKeepAliveInterval(config.keepAliveSeconds());
        options.setConnectionTimeout(config.connectionTimeoutSeconds());
        options.setAutomaticReconnect(true);

        client.setCallback(this);
        client.connect(options);
        client.subscribe(
                new String[] {Topics.allDevices("telemetry"), Topics.allDevices("status")},
                new int[] {config.subscriptionQos(), config.subscriptionQos()});

        System.out.println("gateway subscribed to " + Topics.allDevices("telemetry")
                + " and " + Topics.allDevices("status") + " at " + config.brokerUrl());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String deviceId = Topics.deviceIdOf(topic);
        String kind = Topics.kindOf(topic);
        if (deviceId == null || kind == null) {
            // A shared broker carries other publishers' traffic; an
            // unrecognised topic is data to count, not an error.
            metrics.unroutableMessage();
            return;
        }
        switch (kind) {
            case "telemetry" -> handleTelemetry(topic, deviceId, message.getPayload());
            case "status" -> handlePresence(topic, deviceId, message.getPayload());
            default -> metrics.unroutableMessage();
        }
    }

    private void handleTelemetry(String topic, String deviceId, byte[] payload) {
        Telemetry telemetry;
        try {
            telemetry = parser.parse(payload, 0, payload.length);
        } catch (MalformedPayloadException e) {
            metrics.telemetryMalformed();
            registry.recordRejection(deviceId);
            System.err.println("rejected malformed payload on " + topic + ": " + e.getMessage());
            return;
        }

        if (!deviceId.equals(telemetry.deviceId())) {
            // The topic and the body disagree about who sent this. Trusting
            // either one would attribute a reading to the wrong device, so the
            // reading is dropped rather than guessed at.
            metrics.telemetryInvalid();
            registry.recordRejection(deviceId);
            System.err.println("rejected reading on " + topic
                    + ": body claims deviceId '" + telemetry.deviceId() + "'");
            return;
        }

        try {
            TelemetryValidator.validate(telemetry);
        } catch (ValidationException e) {
            metrics.telemetryInvalid();
            registry.recordRejection(deviceId);
            System.err.println("rejected invalid reading on " + topic + ": " + e.getMessage());
            return;
        }

        registry.recordTelemetry(telemetry, clock.millis());
        metrics.telemetryAccepted();
    }

    private void handlePresence(String topic, String deviceId, byte[] payload) {
        String raw = new String(payload, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            // A zero-length retained message is how a retained presence entry
            // is cleared. Not an error, and not a presence event.
            return;
        }
        try {
            registry.recordPresence(deviceId, Presence.valueOf(raw), clock.millis());
            metrics.presenceEvent();
        } catch (IllegalArgumentException e) {
            metrics.unroutableMessage();
            System.err.println("unknown presence '" + raw + "' on " + topic);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        metrics.connectionLost();
        System.err.println("gateway lost its broker connection: " + cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // The gateway only subscribes in this phase.
    }

    @Override
    public void close() throws MqttException {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } finally {
            client.close();
        }
    }
}
