package io.fleet.edge.mqtt;

import io.fleet.common.Presence;
import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Arrays;
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
 * failure.
 *
 * <p>No exception is swallowed. Every MQTT failure becomes a
 * {@link SinkException}, which the harness counts and reports, because a
 * pipeline that silently stops delivering looks identical to one with nothing
 * to deliver.
 *
 * <p>Not intended for Pillar A measurement: see {@link #publish} on the copy
 * the client library forces, and use the counting sink for that comparison.
 */
public final class MqttTelemetrySink implements TelemetrySink {

    /** Applied when a reconnect attempt fails, so a down broker is retried, not hammered. */
    private static final long RECONNECT_BACKOFF_MILLIS = 1_000L;

    private static final int PRESENCE_QOS = 1;

    private final String deviceId;
    private final MqttConfig config;
    private final String telemetryTopic;
    private final String statusTopic;
    private final MqttClient client;
    private final MqttConnectOptions connectOptions;
    private final LongAdder payloads;
    private final LongAdder bytes;

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
            LongAdder bytes) throws SinkException {

        this.deviceId = deviceId;
        this.config = config;
        this.telemetryTopic = Topics.telemetry(deviceId);
        this.statusTopic = Topics.status(deviceId);
        this.interruptAfterPublishes = interruptAfterPublishes;
        this.interruptDurationMillis = interruptDurationMillis;
        this.payloads = payloads;
        this.bytes = bytes;

        try {
            this.client = new MqttClient(
                    config.brokerUrl(), config.clientId(deviceId), new MemoryPersistence());
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
            client.connect(connectOptions);
            announce(Presence.ONLINE);
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

    public String telemetryTopic() {
        return telemetryTopic;
    }

    public synchronized boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public synchronized void close() throws SinkException {
        try {
            if (client.isConnected()) {
                // Retained OFFLINE then a real DISCONNECT: the broker records the
                // device as gone without treating the shutdown as a failure.
                announce(Presence.OFFLINE);
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            throw new SinkException("device " + deviceId + " failed to disconnect cleanly", e);
        }
    }
}
