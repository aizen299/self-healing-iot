package io.fleet.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.fleet.common.DeviceEventType;
import io.fleet.common.FleetTopic;
import io.fleet.common.HeartbeatParser;
import io.fleet.common.MalformedPayloadException;
import io.fleet.common.TelemetryParser;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryStore;
import io.fleet.common.TelemetryValidator;
import io.fleet.common.Topics;
import io.fleet.common.StoreException;
import io.fleet.common.ValidationException;
import io.fleet.gateway.kafka.NoOpForwarder;
import io.fleet.gateway.kafka.TelemetryForwarder;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Subscribes to the fleet's topics and feeds the registry.
 *
 * <p>Nothing thrown from a message callback may escape. Paho catches whatever
 * does, logs it, and carries on — so the gateway would keep reporting itself
 * healthy while silently dropping every message of that shape. Each failure is
 * instead classified, counted, and logged with the topic that caused it.
 */
public final class MqttIngestor implements MqttCallback, EventPublisher, AutoCloseable {

    private static final int EVENT_QOS = 1;

    private final GatewayConfig config;
    private final DeviceRegistry registry;
    private final GatewayMetrics metrics;
    private final TelemetryParser parser = new TelemetryParser();
    private final HeartbeatParser heartbeatParser = new HeartbeatParser();
    private final JsonFactory json = new JsonFactory();
    private final HealthPolicy policy;
    private final TelemetryStore store;
    private final TelemetryForwarder forwarder;
    /**
     * Set after construction to break a genuine cycle: the monitor announces
     * transitions through this class as its EventPublisher, and this class
     * produces transitions the monitor must announce. A setter states that
     * plainly rather than hiding it behind a supplier.
     */
    private volatile Consumer<HealthTransition> onTransition = transition -> { };
    private final Clock clock;
    private final MqttClient client;

    public MqttIngestor(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics) {
        this(config, registry, metrics, config.healthPolicy(),
                new io.fleet.gateway.store.NoOpTelemetryStore(), Clock.systemUTC());
    }

    public MqttIngestor(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics,
            HealthPolicy policy, TelemetryStore store) {
        this(config, registry, metrics, policy, store, Clock.systemUTC());
    }

    /** @param policy shared with the monitor so one object defines the rules */
    public MqttIngestor(
            GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics,
            HealthPolicy policy, TelemetryStore store, Clock clock) {
        this(config, registry, metrics, policy, store,
                new NoOpForwarder(), clock);
    }

    public MqttIngestor(
            GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics,
            HealthPolicy policy, TelemetryStore store,
            TelemetryForwarder forwarder, Clock clock) {
        this.forwarder = forwarder;
        this.config = config;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
        this.policy = policy;
        this.store = store;
        try {
            this.client = new MqttClient(
                    config.brokerUrl(), config.clientId(), new MemoryPersistence());
            // Bounds every synchronous call. Without it a half-open connection
            // lets connect or disconnect hang indefinitely — and a gateway that
            // hangs against a sick broker is worse than a device doing the
            // same, since the gateway is what is supposed to notice.
            this.client.setTimeToWait(
                    TimeUnit.SECONDS.toMillis(config.operationTimeoutSeconds()));
        } catch (MqttException e) {
            throw new IllegalStateException("could not create the gateway MQTT client", e);
        }
    }

    /** Routes health transitions this ingestor observes; see the field's note. */
    public void onTransition(Consumer<HealthTransition> listener) {
        this.onTransition = listener;
    }

    /** Connects and subscribes to telemetry, heartbeats, and presence. */
    public void start() throws IngestException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(config.cleanSession());
        options.setKeepAliveInterval(config.keepAliveSeconds());
        options.setConnectionTimeout(config.connectionTimeoutSeconds());
        options.setAutomaticReconnect(true);

        try {
            client.setCallback(this);
            client.connect(options);
            client.subscribe(
                    new String[] {
                        Topics.allDevices("telemetry"),
                        Topics.allDevices("heartbeat"),
                        Topics.allDevices("status")},
                    new int[] {
                        config.subscriptionQos(),
                        config.subscriptionQos(),
                        config.subscriptionQos()});
        } catch (MqttException e) {
            throw new IngestException(
                    "gateway could not subscribe at " + config.brokerUrl(), e);
        }

        System.out.println("gateway subscribed to telemetry, heartbeat, and status for "
                + Topics.allDevices("*") + " at " + config.brokerUrl());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // The outer boundary that makes this class's promise real: no failure
        // in parsing, validation, or bookkeeping may reach Paho, where it would
        // become an unnoticed dropped message.
        try {
            dispatch(topic, message);
        } catch (RuntimeException e) {
            metrics.handlerError();
            System.err.println("error handling a message on " + topic + ": " + e);
        }
    }

    private void dispatch(String topic, MqttMessage message) {
        FleetTopic parsed = FleetTopic.parse(topic);
        if (parsed == null) {
            // A shared broker carries other publishers' traffic; an
            // unrecognised topic is data to count, not an error.
            metrics.unroutableMessage();
            return;
        }
        switch (parsed.kind()) {
            case "telemetry" -> handleTelemetry(topic, parsed.deviceId(), message.getPayload());
            case "heartbeat" -> handleHeartbeat(topic, parsed.deviceId(), message.getPayload());
            case "status" -> handlePresence(topic, parsed.deviceId(), message.getPayload());
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

        long receivedAt = clock.millis();
        registry.recordTelemetry(telemetry, receivedAt);
        metrics.telemetryAccepted();

        try {
            store.record(telemetry, receivedAt);
        } catch (StoreException e) {
            // Counted, not fatal. A store that cannot keep up must not stop
            // the gateway detecting failures — losing history is bad, losing
            // detection is worse.
            metrics.storeError();
            System.err.println("could not persist a reading from " + deviceId
                    + ": " + e.getMessage());
        }

        // The exact bytes that arrived: the MQTT payload is already the wire
        // format, so there is no second encoder that could drift from the first.
        forwarder.forwardTelemetry(deviceId, payload, 0, payload.length);
    }

    private void handleHeartbeat(String topic, String deviceId, byte[] payload) {
        try {
            var heartbeat = heartbeatParser.parse(payload, 0, payload.length);
            if (!deviceId.equals(heartbeat.deviceId())) {
                // Accepting this would let one device assert liveness on
                // another's behalf, which is the one lie that would defeat
                // failure detection entirely.
                metrics.heartbeatMalformed();
                System.err.println("rejected heartbeat on " + topic
                        + ": body claims deviceId '" + heartbeat.deviceId() + "'");
                return;
            }
        } catch (MalformedPayloadException e) {
            metrics.heartbeatMalformed();
            System.err.println("rejected malformed heartbeat on " + topic + ": " + e.getMessage());
            return;
        }

        metrics.heartbeatAccepted();
        // Receipt time, not the device's clock: a wedged device may be wrong
        // about the time, and a clock jump must not buy it a reprieve.
        Optional<HealthTransition> transition =
                registry.recordHeartbeat(deviceId, clock.millis(), policy);
        transition.ifPresent(onTransition);
    }

    private void handlePresence(String topic, String deviceId, byte[] payload) {
        String raw = new String(payload, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            // A zero-length retained message is how a retained presence entry
            // is cleared. Not an error, and not a presence event.
            return;
        }
        try {
            Presence presence = Presence.valueOf(raw);
            metrics.presenceEvent();
            registry.recordPresence(deviceId, presence, clock.millis(), policy)
                    .ifPresent(onTransition);
        } catch (IllegalArgumentException e) {
            metrics.invalidPresence();
            System.err.println("unknown presence '" + raw + "' on " + topic);
        }
    }

    /**
     * Publishes a transition on the device's events topic.
     *
     * <p>QoS 1, not retained. Recovery in Phase 9 acts on these, so losing one
     * would leave a device failed and unattended — but an event is a moment
     * rather than a state, and retaining it would replay old failures to every
     * new subscriber.
     *
     * <p>Never throws: a failure to announce must not stop the sweep that
     * produced it, or one unreachable consumer would halt detection for the
     * whole fleet.
     */
    @Override
    public void publish(HealthTransition transition) {
        Optional<DeviceEventType> announceable = transition.eventType();
        if (announceable.isEmpty()) {
            return;
        }
        DeviceEventType type = announceable.get();
        try {
            client.publish(Topics.events(transition.deviceId()),
                    encodeEvent(transition, type), EVENT_QOS, false);
        } catch (MqttException | IOException | RuntimeException e) {
            metrics.eventPublishFailure();
            System.err.println("could not publish " + type + " for "
                    + transition.deviceId() + ": " + e);
        }
    }

    private byte[] encodeEvent(HealthTransition transition, DeviceEventType type)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(192);
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField("deviceId", transition.deviceId());
            generator.writeStringField("event", type.name());
            generator.writeStringField("from", transition.from().name());
            generator.writeStringField("to", transition.to().name());
            generator.writeNumberField("at", transition.atMillis());
            generator.writeNumberField("missedHeartbeats", transition.missedHeartbeats());
            generator.writeNumberField("recoveryDurationMillis",
                    transition.recoveryDurationMillis());
            generator.writeEndObject();
        }
        return out.toByteArray();
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
    public void close() throws IngestException {
        try {
            if (client.isConnected()) {
                client.disconnect(TimeUnit.SECONDS.toMillis(config.operationTimeoutSeconds()));
            }
        } catch (MqttException e) {
            throw new IngestException("gateway failed to disconnect cleanly", e);
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            client.close();
        } catch (MqttException e) {
            // Reported, never swallowed. Throwing here would mask a disconnect
            // failure already on its way up.
            System.err.println("gateway failed to close its MQTT client: " + e);
        }
    }
}
