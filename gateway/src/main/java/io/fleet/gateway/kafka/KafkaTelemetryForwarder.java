package io.fleet.gateway.kafka;

import io.fleet.common.DeviceEventCodec;
import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.KafkaTopics;
import io.fleet.common.LazyResource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.time.Clock;
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
 *
 * <p><b>The producer is built on that thread too, and rebuilt until it
 * works.</b> Constructing a {@code KafkaProducer} resolves
 * {@code bootstrap.servers} and throws when nothing resolves, so building it
 * eagerly meant a gateway that started before Kafka forwarded nothing for the
 * life of the process — the normal case in a namespace where every pod starts
 * at once. It is also the second thread-safety reason: that constructor does
 * DNS, which is not work for the MQTT callback thread.
 */
public final class KafkaTelemetryForwarder implements TelemetryForwarder {

    /** How long shutdown waits for the queue to drain before giving up on it. */
    private static final long DRAIN_TIMEOUT_MILLIS = 2_000L;

    /** How long the sender thread waits between looks at an empty queue. */
    private static final long IDLE_WAIT_MILLIS = 200L;

    /** How long to wait after failing to build the producer before trying again. */
    private static final long RETRY_INTERVAL_MILLIS = 10_000L;

    /** Bounded, so an unreachable broker cannot outlast the container's stop grace. */
    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final LazyResource<Producer<String, byte[]>> producer;
    // The codec, not a private encoder. Since Phase 9 the operator reads
    // these events back off device.failures, so the format has a second
    // reader and belongs in common — the same move Phase 6 made for the
    // telemetry parser, and for the same reason.
    private final DeviceEventCodec codec = new DeviceEventCodec();
    private final LongAdder failures = new LongAdder();
    private final BlockingQueue<ProducerRecord<String, byte[]>> pending;
    private final Thread sender;
    private volatile boolean running = true;

    public KafkaTelemetryForwarder(ForwarderConfig config) {
        this(lazyProducer(config), config.queueCapacity());
    }

    /** @param producer injected so a test can exercise this without a broker */
    KafkaTelemetryForwarder(Producer<String, byte[]> producer, int queueCapacity) {
        this(alreadyOpen(producer), queueCapacity);
    }

    /** @param producer injected so a test can drive the retry without waiting on it */
    KafkaTelemetryForwarder(LazyResource<Producer<String, byte[]>> producer,
            int queueCapacity) {
        this.producer = producer;
        this.pending = new ArrayBlockingQueue<>(queueCapacity);
        this.sender = new Thread(this::drain, "gateway-kafka-forwarder");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    private static LazyResource<Producer<String, byte[]>> lazyProducer(ForwarderConfig config) {
        // Built once and captured, so a retry does not re-derive it — and so
        // the closure holds a small Properties rather than the config record.
        Properties props = properties(config);
        return new LazyResource<>("the kafka producer",
                () -> new KafkaProducer<>(props),
                producer -> producer.close(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)),
                RETRY_INTERVAL_MILLIS, Clock.systemUTC());
    }

    /** Wraps a producer a test supplied, so there is one path through this class. */
    private static LazyResource<Producer<String, byte[]>> alreadyOpen(
            Producer<String, byte[]> producer) {
        return new LazyResource<>("the injected producer", () -> producer,
                open -> open.close(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)),
                RETRY_INTERVAL_MILLIS, Clock.systemUTC());
    }

    /**
     * Drains the queue onto the producer.
     *
     * <p>This thread is allowed to block; the MQTT callback thread is not.
     *
     * <p>While the producer cannot be built, records are left in the queue
     * rather than drained into nothing. The queue is bounded and drops when
     * full, so an outage shorter than the queue depth costs nothing and a
     * longer one degrades exactly as a full queue always did.
     */
    private void drain() {
        while (running || !pending.isEmpty()) {
            try {
                Producer<String, byte[]> kafka = producer.get();
                if (kafka == null) {
                    if (!running) {
                        // Shutting down with no producer: waiting out the retry
                        // interval would only delay the stop, and there is
                        // nothing to deliver these records to.
                        return;
                    }
                    Thread.sleep(IDLE_WAIT_MILLIS);
                    continue;
                }
                ProducerRecord<String, byte[]> record =
                        pending.poll(IDLE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                if (record != null) {
                    dispatch(kafka, record);
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
            value = codec.encode(event);
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

    private void dispatch(Producer<String, byte[]> kafka,
            ProducerRecord<String, byte[]> record) {
        try {
            kafka.send(record, (metadata, exception) -> {
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
        // Bounded, and never throws — see LazyResource.close. The no-argument
        // producer close waits for every buffered record to reach its delivery
        // timeout, so an unreachable broker at shutdown could outlast the
        // container's stop grace and get the gateway SIGKILLed mid-shutdown:
        // skipping the store flush and the clean MQTT disconnect, and turning
        // an orderly stop into a Last Will storm.
        producer.close();
    }
}
