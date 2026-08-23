package io.fleet.edge.mqtt;

import io.fleet.common.Presence;
import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publishes one device's telemetry over MQTT.
 *
 * <p>One instance per device, each with its own broker connection, so the
 * Last Will identifies a single device rather than the whole fleet.
 *
 * <p>Presence is maintained on {@code fleet/{id}/status}: retained
 * {@code ONLINE} on connect, retained {@code OFFLINE} as the Last Will, and
 * retained {@code OFFLINE} plus a proper DISCONNECT on clean shutdown. The
 * distinction matters — a clean shutdown must <em>not</em> leave the broker
 * firing a will, or Phase 4 would read every orderly stop as a device
 * failure. {@link #abort()} exists for the opposite case.
 *
 * <p>No exception is swallowed. Every MQTT failure becomes a
 * {@link SinkException}, which the harness counts and reports, because a
 * pipeline that silently stops delivering looks identical to one with nothing
 * to deliver. Unexpected connection losses are counted separately from
 * injected ones so a broker restart cannot be mistaken for a device fault.
 *
 * <p>Not intended for Pillar A measurement: see {@link #publish} on the copy
 * the client library forces, and use the counting sink for that comparison.
 */
public final class MqttTelemetrySink implements TelemetrySink, MqttCallback {

    /** Applied when a reconnect attempt fails, so a down broker is retried, not hammered. */
    private static final long RECONNECT_BACKOFF_MILLIS = 1_000L;

    private static final int PRESENCE_QOS = 1;

    private final String deviceId;
    private final MqttConfig config;
    private final String statusTopic;
    private final MqttClient client;
    private final MqttConnectOptions connectOptions;
    private final LongAdder payloads;
    private final LongAdder bytes;
    private final LongAdder connectionLosses;

    private final long interruptAfterPublishes;
    private final long interruptDurationMillis;

    private long publishAttempts;
    private long resumeAtMillis;
    private boolean interruptionTriggered;

    MqttTelemetrySink(
            String deviceId,
            MqttConfig config,
            long interruptAfterPublishes,
            long interruptDurationMillis,
            LongAdder payloads,
            LongAdder bytes,
            LongAdder connectionLosses) throws SinkException {

        this.deviceId = deviceId;
        this.config = config;
        this.statusTopic = io.fleet.common.Topics.status(deviceId);
        this.interruptAfterPublishes = interruptAfterPublishes;
        this.interruptDurationMillis = interruptDurationMillis;
        this.payloads = payloads;
        this.bytes = bytes;
        this.connectionLosses = connectionLosses;

        try {
            this.client = new MqttClient(
                    config.brokerUrl(), config.clientId(deviceId), new MemoryPersistence());
            // Bounds how long any synchronous call may block. Without it, a
            // half-open connection lets a QoS 1 publish or a disconnect stall
            // shutdown indefinitely, once per device.
            this.client.setTimeToWait(
                    TimeUnit.SECONDS.toMillis(config.operationTimeoutSeconds()));
            this.client.setCallback(this);
        } catch (MqttException e) {
            throw new SinkException("could not create MQTT client for " + deviceId, e);
        }
        this.connectOptions = buildConnectOptions();
    }

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(config.cleanSession());
        options.setKeepAliveInterval(config.keepAliveSeconds());
        options.setConnectionTimeout(config.connectionTimeoutSeconds());
        options.setAutomaticReconnect(config.automaticReconnect());
        if (config.publishRetainedStatus()) {
            options.setWill(statusTopic, Presence.OFFLINE.payload(), PRESENCE_QOS, true);
        }
        return options;
    }

    /** Establishes the connection and announces presence. */
    synchronized void connect() throws SinkException {
        try {
            client.connect(connectOptions);
            announce(Presence.ONLINE);
        } catch (MqttException e) {
            throw new SinkException(
                    "device " + deviceId + " could not connect to " + config.brokerUrl(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The payload is copied unconditionally. Paho accepts only a whole
     * array, and callers may hand over a slice of a buffer they reuse. Copying
     * always — rather than only when the slice is partial — keeps the work
     * identical for both device variants, since the constrained variant passes
     * a slice of its fixed buffer while the naive variant passes an
     * exactly-sized array. Conditional copying would quietly hand the naive
     * variant an advantage in any measurement taken through this sink.
     */
    @Override
    public synchronized void publish(String topic, byte[] payload, int offset, int length)
            throws SinkException {

        publishAttempts++;

        if (shouldTriggerInterruption()) {
            triggerInterruption();
            throw new SinkException(
                    "device " + deviceId + " lost network after " + interruptAfterPublishes
                            + " publishes (simulated)");
        }
        if (resumeAtMillis > 0L) {
            awaitRecovery();
        }

        byte[] out = Arrays.copyOfRange(payload, offset, offset + length);
        try {
            client.publish(topic, out, config.qos(), false);
        } catch (MqttException e) {
            throw new SinkException("device " + deviceId + " failed to publish to " + topic, e);
        }
        payloads.increment();
        bytes.add(length);
    }

    private boolean shouldTriggerInterruption() {
        return interruptAfterPublishes > 0L
                && !interruptionTriggered
                && publishAttempts > interruptAfterPublishes;
    }

    /**
     * Drops the connection the way a severed network does.
     *
     * <p>{@code sendDisconnectPacket=false} is the whole point: the broker
     * sees an ungraceful drop and fires the Last Will, which is precisely the
     * signal Phase 4 must detect. A normal disconnect would suppress it.
     *
     * <p>Fires once per device per run — this models a single deterministic
     * outage, not a recurring one.
     */
    private void triggerInterruption() throws SinkException {
        interruptionTriggered = true;
        resumeAtMillis = System.currentTimeMillis() + interruptDurationMillis;
        try {
            client.disconnectForcibly(0L, 0L, false);
        } catch (MqttException e) {
            throw new SinkException(
                    "device " + deviceId + " failed to simulate network loss", e);
        }
    }

    /** Throws while still down; reconnects and returns once the outage has elapsed. */
    private void awaitRecovery() throws SinkException {
        long now = System.currentTimeMillis();
        if (now < resumeAtMillis) {
            throw new SinkException(
                    "device " + deviceId + " is offline for another "
                            + (resumeAtMillis - now) + " ms (simulated)");
        }
        try {
            // Paho's automatic reconnect may have restored the link already.
            // Calling connect() on a live client throws ALREADY_CONNECTED,
            // which would back the device off a second at a time and leave it
            // permanently mute for the rest of the run.
            if (!client.isConnected()) {
                client.connect(connectOptions);
                announce(Presence.ONLINE);
            }
            resumeAtMillis = 0L;
        } catch (MqttException e) {
            resumeAtMillis = now + RECONNECT_BACKOFF_MILLIS;
            throw new SinkException("device " + deviceId + " failed to reconnect", e);
        }
    }

    private void announce(Presence presence) throws MqttException {
        if (config.publishRetainedStatus()) {
            client.publish(statusTopic, presence.payload(), PRESENCE_QOS, true);
        }
    }

    /**
     * Releases the connection the way a dead device would: no DISCONNECT, so
     * the broker fires the Last Will.
     *
     * <p>Used when a device crashes. Going through {@link #close()} instead
     * would send a clean DISCONNECT and suppress the will, leaving the broker
     * — and therefore Phase 4 — believing the device shut down on purpose.
     */
    synchronized void abort() throws SinkException {
        try {
            if (client.isConnected()) {
                client.disconnectForcibly(0L, 0L, false);
            }
        } catch (MqttException e) {
            throw new SinkException("device " + deviceId + " failed to abort its connection", e);
        } finally {
            closeQuietly();
        }
    }

    @Override
    public synchronized void close() throws SinkException {
        try {
            if (client.isConnected()) {
                // Retained OFFLINE then a real DISCONNECT: the broker records the
                // device as gone without treating the shutdown as a failure.
                announce(Presence.OFFLINE);
                client.disconnect(TimeUnit.SECONDS.toMillis(config.operationTimeoutSeconds()));
            }
        } catch (MqttException e) {
            throw new SinkException("device " + deviceId + " failed to disconnect cleanly", e);
        } finally {
            // In a finally block because a failed disconnect must still release
            // the client's network module and threads; otherwise a broker that
            // dies during shutdown leaks one client per device.
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            client.close();
        } catch (MqttException e) {
            // Reported, never swallowed. Throwing from here would mask the
            // disconnect failure that is already on its way up.
            System.err.println("device " + deviceId + " failed to close its MQTT client: " + e);
        }
    }

    // --- MqttCallback: observes losses this sink did not cause -------------

    /**
     * A connection loss the sink did not inject — a broker restart, or a real
     * network fault. Counted separately so an experiment cannot mistake
     * infrastructure trouble for a device-side result.
     */
    @Override
    public void connectionLost(Throwable cause) {
        connectionLosses.increment();
        System.err.println("device " + deviceId + " lost its broker connection: " + cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // This client only publishes; it subscribes to nothing.
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Delivery is accounted for at publish time.
    }
}
