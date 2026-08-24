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
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Forwards telemetry and health events to Kafka.
 *
 * <p>Everything is keyed by device id. That is what puts a device's readings
 * and its failures on the same partition and keeps them ordered relative to
 * each other, which any windowed aggregation downstream depends on.
 *
 * <p>Handed off to a dedicated thread through a bounded queue, and never
 * produced from the caller's thread. This is not tidiness — it is what makes
 * the claim "Kafka can be down and detection still works" actually true.
 * {@code KafkaProducer.send} blocks for up to {@code max.block.ms} when
 * metadata is unavailable or the record buffer is full, and the caller here is
 * the MQTT callback thread: the one that records heartbeats. Blocking it means
 * heartbeats stop being recorded, the monitor sees devices go quiet, and an
 * unreachable broker gets reported as a fleet-wide device failure.
 *
 * <p>The queue is bounded and drops when full rather than blocking, for the
 * same reason. A downstream copy is worth less than the detection it would
 * otherwise stall; drops are counted so the loss is visible.
 */
public final class KafkaTelemetryForwarder implements TelemetryForwarder {

    /** How long shutdown waits for the queue to drain before giving up on it. */
    private static final long DRAIN_TIMEOUT_MILLIS = 2_000L;

    private final Producer<String, byte[]> producer;
    private final JsonFactory json = new JsonFactory();
    private final LongAdder failures = new LongAdder();
    private final BlockingQueue<ProducerRecord<String, byte[]>> pending;
    private final Thread sender;
    private volatile boolean running = true;

    public KafkaTelemetryForwarder(ForwarderConfig config) {
        this(new KafkaProducer<>(properties(config)), config.queueCapacity());
    }

    /** @param producer injected so a test can exercise this without a broker */
    KafkaTelemetryForwarder(Producer<String, byte[]> producer, int queueCapacity) {
        this.producer = producer;
        this.pending = new ArrayBlockingQueue<>(queueCapacity);
        this.sender = new Thread(this::drain, "gateway-kafka-forwarder");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    /**
     * Drains the queue onto the producer.
     *
     * <p>This thread is allowed to block; the MQTT callback thread is not.
     */
    private void drain() {
        while (running || !pending.isEmpty()) {
            try {
                ProducerRecord<String, byte[]> record =
                        pending.poll(200L, TimeUnit.MILLISECONDS);
                if (record != null) {
                    dispatch(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Queues a record, dropping it if the queue is full.
     *
     * <p>{@code offer} rather than {@code put}: blocking here would put the
     * back-pressure straight back onto the thread this class exists to keep
     * free.
     */
    private void enqueue(String topic, String key, byte[] value) {
        if (!pending.offer(new ProducerRecord<>(topic, key, value))) {
            failures.increment();
        }
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
        enqueue(KafkaTopics.TELEMETRY_RAW, deviceId, value);
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
        enqueue(KafkaTopics.DEVICE_EVENTS, event.deviceId(), value);
        if (event.event() == DeviceEventType.DEVICE_OFFLINE) {
            enqueue(KafkaTopics.DEVICE_FAILURES, event.deviceId(), value);
        } else if (event.event() == DeviceEventType.DEVICE_RECOVERED) {
            enqueue(KafkaTopics.DEVICE_RECOVERY, event.deviceId(), value);
        }
    }

    private void dispatch(ProducerRecord<String, byte[]> record) {
        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    failures.increment();
                    System.err.println("kafka send to " + record.topic() + " failed: "
                            + exception.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() can throw synchronously when the buffer is full or the
            // producer is closed. Counted like any other delivery failure.
            failures.increment();
            System.err.println("kafka send to " + record.topic() + " rejected: "
                    + e.getMessage());
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
        // toByteArray, not toString().getBytes(): the round trip through a
        // String costs two conversions and is silently lossy for anything
        // that is not valid UTF-8.
        return out.toByteArray();
    }

    @Override
    public long forwardFailures() {
        return failures.sum();
    }

    @Override
    public void close() {
        running = false;
        try {
            sender.join(DRAIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            // Bounded. The no-argument close waits for every buffered record to
            // reach its delivery timeout, so an unreachable broker at shutdown
            // could outlast the container's stop grace and get the gateway
            // SIGKILLed mid-shutdown — skipping the store flush and the clean
            // MQTT disconnect, and turning an orderly stop into a Last Will storm.
            producer.close(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            System.err.println("kafka producer failed to close cleanly: " + e.getMessage());
        }
    }
}
