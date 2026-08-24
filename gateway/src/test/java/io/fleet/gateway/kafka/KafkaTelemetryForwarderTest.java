package io.fleet.gateway.kafka;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.common.KafkaTopics;
import io.fleet.common.LazyResource;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forwarder against a MockProducer.
 *
 * <p>The package-private constructor exists precisely so this is possible
 * without a broker, and until now nothing used it. Four things are worth
 * pinning down: that a failure reaches both the general and the
 * failure-specific topic, that telemetry bytes pass through untouched, that a
 * producer which throws is counted rather than allowed to escape, and that a
 * full queue drops instead of blocking the caller.
 *
 * <p>The last two cover the producer being built lazily on the sender thread,
 * which is what stops a gateway that started before Kafka forwarding nothing
 * for the life of the process.
 */
class KafkaTelemetryForwarderTest {

    private static final long T0 = 1_787_500_000_000L;

    private static MockProducer<String, byte[]> autoCompleting() {
        return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    @Test
    @DisplayName("telemetry is forwarded byte-for-byte on the device's key")
    void telemetryIsForwardedVerbatim() throws Exception {
        MockProducer<String, byte[]> producer = autoCompleting();
        byte[] wire = "{\"deviceId\":\"device-001\",\"temp\":21.40}"
                .getBytes(StandardCharsets.UTF_8);

        try (KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(producer, 64)) {
            // A slice of a larger buffer, as the constrained device sends.
            byte[] buffer = new byte[wire.length + 32];
            System.arraycopy(wire, 0, buffer, 4, wire.length);
            forwarder.forwardTelemetry("device-001", buffer, 4, wire.length);

            awaitUntil(() -> producer.history().size() == 1);
        }

        ProducerRecord<String, byte[]> sent = producer.history().get(0);
        assertEquals(KafkaTopics.TELEMETRY_RAW, sent.topic());
        assertEquals("device-001", sent.key(),
                "keyed by device so its records share a partition and stay ordered");
        assertEquals(new String(wire, StandardCharsets.UTF_8),
                new String(sent.value(), StandardCharsets.UTF_8),
                "only the slice, and unmodified");
    }

    @Test
    @DisplayName("a failure lands on both device.events and device.failures")
    void failuresGoToTheirOwnTopicAsWell() throws Exception {
        MockProducer<String, byte[]> producer = autoCompleting();

        try (KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(producer, 64)) {
            forwarder.forwardEvent(new DeviceEventRecord("device-001",
                    DeviceEventType.DEVICE_OFFLINE, DeviceHealth.SUSPECTED,
                    DeviceHealth.OFFLINE, T0, 4, -1L));
            awaitUntil(() -> producer.history().size() == 2);
        }

        List<String> topics = producer.history().stream().map(ProducerRecord::topic).toList();
        // Phase 9's controller subscribes only to device.failures, so it cannot
        // see anything it should not act on.
        assertTrue(topics.contains(KafkaTopics.DEVICE_EVENTS), topics.toString());
        assertTrue(topics.contains(KafkaTopics.DEVICE_FAILURES), topics.toString());

        String body = new String(producer.history().get(0).value(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"event\":\"DEVICE_OFFLINE\""), body);
        assertTrue(body.contains("\"missedHeartbeats\":4"), body);
    }

    @Test
    @DisplayName("a recovery goes to device.recovery and not to device.failures")
    void recoveriesAreRoutedCorrectly() throws Exception {
        MockProducer<String, byte[]> producer = autoCompleting();

        try (KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(producer, 64)) {
            forwarder.forwardEvent(new DeviceEventRecord("device-001",
                    DeviceEventType.DEVICE_RECOVERED, DeviceHealth.RECOVERING,
                    DeviceHealth.ONLINE, T0, 0, 2_500L));
            awaitUntil(() -> producer.history().size() == 2);
        }

        List<String> topics = producer.history().stream().map(ProducerRecord::topic).toList();
        assertTrue(topics.contains(KafkaTopics.DEVICE_RECOVERY), topics.toString());
        assertFalse(topics.contains(KafkaTopics.DEVICE_FAILURES),
                "a recovery must not appear on the failure topic: " + topics);
    }

    @Test
    @DisplayName("a producer that throws is counted, never propagated")
    void producerFailuresAreCountedNotThrown() throws Exception {
        MockProducer<String, byte[]> producer = autoCompleting();
        KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(producer, 64);
        producer.close();

        // Sending through a closed producer throws synchronously. The whole
        // no-throw contract rests on that being absorbed.
        byte[] wire = "{}".getBytes(StandardCharsets.UTF_8);
        forwarder.forwardTelemetry("device-001", wire, 0, wire.length);

        awaitUntil(() -> forwarder.forwardFailures() > 0L);
        forwarder.close();
    }

    @Test
    @DisplayName("a full queue drops instead of blocking the caller")
    void afullQueueDropsRatherThanBlocking() throws Exception {
        // A producer that never completes a send, so the drain thread stalls
        // and the queue fills — what an unreachable broker produces.
        MockProducer<String, byte[]> stalled =
                new MockProducer<>(false, new StringSerializer(), new ByteArraySerializer());

        try (KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(stalled, 2)) {
            byte[] wire = "{}".getBytes(StandardCharsets.UTF_8);
            long start = System.currentTimeMillis();
            for (int i = 0; i < 500; i++) {
                forwarder.forwardTelemetry("device-001", wire, 0, wire.length);
            }
            long elapsed = System.currentTimeMillis() - start;

            // This is the point of the design. The caller is the MQTT callback
            // thread; blocking it stops heartbeats being recorded, and the
            // monitor reads that as a fleet-wide device failure.
            assertTrue(elapsed < 2_000L,
                    "forwarding must not block the caller; took " + elapsed + "ms");
            assertTrue(forwarder.forwardFailures() > 0L,
                    "dropped records must be counted so the loss stays visible");
        }
    }

    @Test
    @DisplayName("records wait in the queue while the producer cannot be built")
    void queuesUntilKafkaIsReachable() throws Exception {
        // The bug this replaced: a producer that could not be constructed at
        // startup was permanent, so a gateway that won the race against Kafka
        // forwarded nothing until it was restarted.
        MockProducer<String, byte[]> producer = autoCompleting();
        AtomicBoolean kafkaUp = new AtomicBoolean(false);
        LazyResource<Producer<String, byte[]>> lazy = new LazyResource<>("a test producer",
                () -> {
                    if (!kafkaUp.get()) {
                        throw new IllegalStateException("no resolvable bootstrap urls");
                    }
                    return producer;
                },
                open -> open.close(Duration.ofSeconds(1)), 50L, Clock.systemUTC());

        try (KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(lazy, 64)) {
            byte[] wire = "{}".getBytes(StandardCharsets.UTF_8);
            forwarder.forwardTelemetry("device-001", wire, 0, wire.length);
            awaitUntil(() -> lazy.openFailureCount() > 0L);

            assertTrue(producer.history().isEmpty(), "nothing can have been sent yet");
            assertEquals(0L, forwarder.forwardFailures(),
                    "a record waiting for the broker has not been dropped; draining the"
                            + " queue into nothing would lose an outage's worth of telemetry"
                            + " the queue had room for");

            kafkaUp.set(true);
            awaitUntil(() -> producer.history().size() == 1);
        }
    }

    @Test
    @DisplayName("shutdown does not wait on a producer that will never open")
    void closesPromptlyWhenKafkaNeverArrives() throws Exception {
        // Holding records for a broker that is not there is right while the
        // gateway is running and wrong while it is stopping: the container's
        // stop grace is also paying for the store flush and the MQTT
        // disconnect.
        LazyResource<Producer<String, byte[]>> lazy = new LazyResource<>("a test producer",
                () -> {
                    throw new IllegalStateException("no resolvable bootstrap urls");
                },
                open -> open.close(Duration.ofSeconds(1)), 50L, Clock.systemUTC());
        KafkaTelemetryForwarder forwarder = new KafkaTelemetryForwarder(lazy, 64);

        byte[] wire = "{}".getBytes(StandardCharsets.UTF_8);
        forwarder.forwardTelemetry("device-001", wire, 0, wire.length);
        awaitUntil(() -> lazy.openFailureCount() > 0L);

        long start = System.currentTimeMillis();
        forwarder.close();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1_500L,
                "close must not sit through the retry interval; took " + elapsed + "ms");
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("condition not met within 5s");
    }
}
