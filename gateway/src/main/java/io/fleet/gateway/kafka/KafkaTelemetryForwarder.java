package io.fleet.gateway.kafka;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.KafkaTopics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Forwards telemetry and health events to Kafka.
 *
 * <p>Everything is keyed by device id. That is what puts a device's readings
 * and its failures on the same partition and keeps them ordered relative to
 * each other, which any windowed aggregation downstream depends on.
 *
 * <p>Sends are asynchronous and failures are counted in the callback, never
 * thrown. The gateway's ingestion and detection must not slow down or stop
 * because a broker is unwell — the same priority the store already has, and
 * for the same reason: losing a downstream copy is bad, losing detection is
 * worse.
 */
public final class KafkaTelemetryForwarder implements TelemetryForwarder {

    private final Producer<String, byte[]> producer;
    private final JsonFactory json = new JsonFactory();
    private final LongAdder failures = new LongAdder();

    public KafkaTelemetryForwarder(ForwarderConfig config) {
        this(new KafkaProducer<>(properties(config)));
    }

    /** @param producer injected so a test can drive the topology without a broker */
    KafkaTelemetryForwarder(Producer<String, byte[]> producer) {
        this.producer = producer;
    }

    private static Properties properties(ForwarderConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, config.clientId());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.LINGER_MS_CONFIG, config.lingerMs());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, config.requestTimeoutMs());
        // Bounded rather than infinite. The default lets a send block for two
        // minutes when the broker is unreachable, and this producer is called
        // from the MQTT callback thread — the thread that also does failure
        // detection.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, config.requestTimeoutMs());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Math.max(config.requestTimeoutMs() * 2, config.requestTimeoutMs() + 1));
        // acks=1, not all. There is one broker, so "all" buys nothing here,
        // and telemetry is individually disposable — the pipeline is not the
        // system of record, the gateway's own store is.
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return props;
    }

    @Override
    public void forwardTelemetry(String deviceId, byte[] payload, int offset, int length) {
        // Copied because the caller may be reusing the buffer and the producer
        // holds the array until the send completes.
        byte[] value = Arrays.copyOfRange(payload, offset, offset + length);
        send(KafkaTopics.TELEMETRY_RAW, deviceId, value);
    }

    @Override
    public void forwardEvent(DeviceEventRecord event) {
        byte[] value;
        try {
            value = encode(event);
        } catch (IOException e) {
            failures.increment();
            System.err.println("could not encode a " + event.event() + " for "
                    + event.deviceId() + ": " + e.getMessage());
            return;
        }

        // Every transition goes to the general topic; failures and recoveries
        // also go to their own. Phase 9's recovery controller subscribes only
        // to device.failures, so it cannot see anything it should not act on.
        send(KafkaTopics.DEVICE_EVENTS, event.deviceId(), value);
        if (event.event() == DeviceEventType.DEVICE_OFFLINE) {
            send(KafkaTopics.DEVICE_FAILURES, event.deviceId(), value);
        } else if (event.event() == DeviceEventType.DEVICE_RECOVERED) {
            send(KafkaTopics.DEVICE_RECOVERY, event.deviceId(), value);
        }
    }

    private void send(String topic, String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
                if (exception != null) {
                    failures.increment();
                    System.err.println("kafka send to " + topic + " failed: "
                            + exception.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() can throw synchronously when the buffer is full or the
            // producer is closed. Counted like any other delivery failure;
            // it must not reach the caller.
            failures.increment();
            System.err.println("kafka send to " + topic + " rejected: " + e.getMessage());
        }
    }

    private byte[] encode(DeviceEventRecord event) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(224);
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField("deviceId", event.deviceId());
            generator.writeStringField("event", event.event().name());
            generator.writeStringField("from", event.fromHealth().name());
            generator.writeStringField("to", event.toHealth().name());
            generator.writeNumberField("at", event.atMillis());
            generator.writeNumberField("missedHeartbeats", event.missedHeartbeats());
            generator.writeNumberField("recoveryDurationMillis", event.recoveryDurationMillis());
            generator.writeEndObject();
        }
        return out.toString(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public long forwardFailures() {
        return failures.sum();
    }

    @Override
    public void close() {
        try {
            producer.close();
        } catch (RuntimeException e) {
            System.err.println("kafka producer failed to close cleanly: " + e.getMessage());
        }
    }
}
