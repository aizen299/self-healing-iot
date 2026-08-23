package io.fleet.edge.mqtt;

import io.fleet.common.Presence;
import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the MQTT sink against a real broker.
 *
 * <p>Skipped, not failed, when no broker is listening — a developer without
 * Mosquitto running should still get a green build, while CI and anyone
 * verifying Phase 2 gets real coverage. Start one with:
 *
 * <pre>
 * /opt/homebrew/opt/mosquitto/sbin/mosquitto -p 1883
 * </pre>
 *
 * <p>Device ids are unique per test because retained messages outlive the
 * process; reusing an id would let one run's retained presence leak into the
 * next.
 */
class MqttTelemetrySinkTest {

    private static final String BROKER =
            System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://127.0.0.1:1883");
    private static final long AWAIT_SECONDS = 10L;

    private String deviceId;
    private MqttConfig config;
    private Subscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(brokerReachable(),
                "no MQTT broker at " + BROKER + " — skipping MQTT integration test");
        deviceId = "itest-" + System.nanoTime();
        config = MqttConfig.from(Map.of("MQTT_BROKER_URL", BROKER));
        subscriber = new Subscriber();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (subscriber != null) {
            subscriber.clearRetained(Topics.status(deviceId));
            subscriber.close();
        }
    }

    @Test
    @DisplayName("telemetry reaches a subscriber on the conventional topic")
    void publishesTelemetryToTheBroker() throws Exception {
        subscriber.subscribe(Topics.telemetry(deviceId));

        try (MqttSinkFactory factory = new MqttSinkFactory(config)) {
            TelemetrySink sink = factory.create(deviceId);
            byte[] payload = "{\"deviceId\":\"x\",\"temp\":21.40}".getBytes(StandardCharsets.UTF_8);
            sink.publish(Topics.telemetry(deviceId), payload, 0, payload.length);

            assertEquals("{\"deviceId\":\"x\",\"temp\":21.40}", subscriber.next());
            assertEquals(1L, factory.payloadCount());
            assertEquals(payload.length, factory.byteCount());
        }
    }

    @Test
    @DisplayName("a payload slice publishes only its own bytes")
    void publishesOnlyTheRequestedSlice() throws Exception {
        subscriber.subscribe(Topics.telemetry(deviceId));

        try (MqttSinkFactory factory = new MqttSinkFactory(config)) {
            TelemetrySink sink = factory.create(deviceId);
            // The constrained variant hands over a slice of a larger reused
            // buffer; the trailing bytes must not reach the broker.
            byte[] buffer = new byte[64];
            byte[] content = "SLICE".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(content, 0, buffer, 0, content.length);

            sink.publish(Topics.telemetry(deviceId), buffer, 0, content.length);

            assertEquals("SLICE", subscriber.next());
        }
    }

    @Test
    @DisplayName("presence is ONLINE on connect and OFFLINE after a clean close")
    void maintainsRetainedPresence() throws Exception {
        subscriber.subscribe(Topics.status(deviceId));

        try (MqttSinkFactory factory = new MqttSinkFactory(config)) {
            factory.create(deviceId);
            assertEquals(Presence.ONLINE.name(), subscriber.next(), "expected ONLINE on connect");
        }

        assertEquals(Presence.OFFLINE.name(), subscriber.next(),
                "a clean shutdown must record the device as offline");
    }

    @Test
    @DisplayName("a simulated network loss fires the Last Will, then the device recovers")
    void networkInterruptionFiresTheWillAndReconnects() throws Exception {
        subscriber.subscribe(Topics.status(deviceId));

        try (MqttSinkFactory factory = new MqttSinkFactory(config, 1L, 300L)) {
            TelemetrySink sink = factory.create(deviceId);
            assertEquals(Presence.ONLINE.name(), subscriber.next());

            byte[] payload = "reading".getBytes(StandardCharsets.UTF_8);
            sink.publish(Topics.telemetry(deviceId), payload, 0, payload.length);

            // The second publish trips the interruption: the connection drops
            // without a DISCONNECT, so the broker must publish the will.
            assertThrows(SinkException.class,
                    () -> sink.publish(Topics.telemetry(deviceId), payload, 0, payload.length));
            assertEquals(Presence.OFFLINE.name(), subscriber.next(),
                    "an ungraceful drop must fire the Last Will");

            // Still inside the outage window: publishing fails loudly.
            assertThrows(SinkException.class,
                    () -> sink.publish(Topics.telemetry(deviceId), payload, 0, payload.length));

            Thread.sleep(400L);
            sink.publish(Topics.telemetry(deviceId), payload, 0, payload.length);
            assertEquals(Presence.ONLINE.name(), subscriber.next(),
                    "the device must announce itself again after recovering");
        }
    }

    @Test
    @DisplayName("an unreachable broker fails loudly rather than silently dropping telemetry")
    void unreachableBrokerRaisesSinkException() {
        MqttConfig unreachable = MqttConfig.from(Map.of(
                "MQTT_BROKER_URL", "tcp://127.0.0.1:1",
                "MQTT_CONNECTION_TIMEOUT_SECONDS", "1"));

        try (MqttSinkFactory factory = new MqttSinkFactory(unreachable)) {
            SinkException error =
                    assertThrows(SinkException.class, () -> factory.create(deviceId));
            assertTrue(error.getMessage().contains("could not connect"), error.getMessage());
        } catch (SinkException closeFailure) {
            throw new AssertionError("closing a never-connected factory must not throw",
                    closeFailure);
        }
    }

    private static boolean brokerReachable() {
        URI uri = URI.create(BROKER);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Minimal collecting subscriber. */
    private static final class Subscriber implements MqttCallback, AutoCloseable {

        private final MqttClient client;
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        Subscriber() throws MqttException {
            client = new MqttClient(
                    BROKER, "itest-sub-" + System.nanoTime(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            client.setCallback(this);
            client.connect(options);
        }

        void subscribe(String topic) throws MqttException {
            client.subscribe(topic, 1);
        }

        String next() throws InterruptedException {
            String message = received.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(message, "no message received within " + AWAIT_SECONDS + "s");
            return message;
        }

        void clearRetained(String topic) throws MqttException {
            if (client.isConnected()) {
                // A zero-length retained payload deletes the retained message.
                client.publish(topic, new byte[0], 1, true);
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            received.add(new String(message.getPayload(), StandardCharsets.UTF_8));
        }

        @Override
        public void connectionLost(Throwable cause) {
            // Nothing to do; the test asserts on messages, not on the subscriber's link.
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Not used: this client only subscribes.
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }
}
