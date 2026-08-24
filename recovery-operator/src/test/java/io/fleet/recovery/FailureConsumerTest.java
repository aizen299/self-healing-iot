package io.fleet.recovery;

import io.fleet.common.DeviceEventCodec;
import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.common.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer loop, driven with a {@code MockConsumer}.
 *
 * <p>Exercises what the loop is responsible for and the controller is not:
 * decoding, refusing to act on the wrong event type, surviving garbage, and
 * committing only after the recovery has happened.
 */
class FailureConsumerTest {

    private static final TopicPartition PARTITION =
            new TopicPartition(KafkaTopics.DEVICE_FAILURES, 0);
    private static final long DETECTED_AT = 1_787_500_000_000L;

    private MockConsumer<String, byte[]> kafka;
    private MockProducer<String, byte[]> producer;
    private FakeKubernetesApi cluster;
    private RecoveryController controller;
    private RecoveryPublisher publisher;
    private long offset;

    @BeforeEach
    void setUp() {
        kafka = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        kafka.assign(List.of(PARTITION));
        kafka.updateBeginningOffsets(Map.of(PARTITION, 0L));

        producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        publisher = new RecoveryPublisher(producer);

        cluster = new FakeKubernetesApi();
        cluster.addPod("edge-device-002", "Failed",
                Map.of("app", "edge-device", "device-id", "device-002",
                        "fleet-id", "fleet-local"),
                podManifest());
        controller = new RecoveryController(cluster, new ReplacementFactory(),
                OperatorConfig.from(Map.of()),
                Clock.fixed(Instant.ofEpochMilli(DETECTED_AT + 800), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a failure is recovered and announced on device.recovery")
    void recoversAndAnnounces() {
        offer(event("device-002", DeviceEventType.DEVICE_OFFLINE));

        runOnce();

        assertEquals(1, controller.replacedCount());
        assertEquals(1, producer.history().size());
        var announcement = new String(producer.history().get(0).value(), StandardCharsets.UTF_8);
        assertEquals(KafkaTopics.DEVICE_RECOVERY, producer.history().get(0).topic());
        // device.recovery also carries the gateway's DEVICE_RECOVERED events,
        // which share nothing but the topic. Without a discriminator a
        // consumer has to sniff for fields and breaks when either side gains
        // one.
        assertTrue(announcement.startsWith("{\"kind\":\"recovery-action\""), announcement);
        assertTrue(announcement.contains("\"outcome\":\"REPLACED\""), announcement);
        assertTrue(announcement.contains("\"detectionToReplacementMillis\":800"), announcement);
    }

    @Test
    @DisplayName("the offset is committed only after the recovery")
    void commitsAfterActing() {
        offer(event("device-002", DeviceEventType.DEVICE_OFFLINE));

        // Committing before acting turns an operator crash mid-recovery into a
        // failure nobody ever handles: the event is marked consumed and the
        // device stays dead. Read while the loop is still running, since run()
        // closes the consumer on its way out.
        assertEquals(1L, runOnce().committedOffset,
                "the commit must follow the recovery, not the poll");
        assertEquals(1, controller.replacedCount());
    }

    @Test
    @DisplayName("nothing is committed when no records arrive")
    void doesNotCommitAnEmptyPoll() {
        assertEquals(-1L, runOnce().committedOffset,
                "an idle operator should not be writing offsets every poll");
    }

    @Test
    @DisplayName("a non-failure event on the topic is refused")
    void ignoresAnythingThatIsNotAFailure() {
        // device.failures should only ever carry DEVICE_OFFLINE, but this
        // operator deletes pods, and "the topic is supposed to" is not a safe
        // basis for that.
        offer(event("device-002", DeviceEventType.DEVICE_RECOVERED));

        Run run = runOnce();

        assertEquals(1, run.ignoredCount());
        assertEquals(0, controller.replacedCount());
        assertTrue(cluster.deleted.isEmpty(), "no pod should have been touched");
    }

    @Test
    @DisplayName("a duration is published only when something was replaced")
    void doesNotPublishAMeaninglessDuration() {
        // The device is alive, so no replacement happens and there is nothing
        // for a duration to measure. Emitting the subtraction anyway would put
        // a number on the topic that looks like a latency and is not one —
        // a replayed event was seen reporting a 29-second "replacement".
        cluster.addPod("edge-device-004", "Running",
                Map.of("app", "edge-device", "device-id", "device-004",
                        "fleet-id", "fleet-local"),
                podManifest());
        offer(event("device-004", DeviceEventType.DEVICE_OFFLINE));

        runOnce();

        var announcement = new String(producer.history().get(0).value(), StandardCharsets.UTF_8);
        assertTrue(announcement.contains("\"outcome\":\"NOT_NEEDED\""), announcement);
        assertTrue(announcement.contains("\"detectionToReplacementMillis\":null"), announcement);
    }

    @Test
    @DisplayName("a malformed record is dropped and the next one still handled")
    void survivesGarbage() {
        offer("{not json".getBytes(StandardCharsets.UTF_8));
        offer(event("device-002", DeviceEventType.DEVICE_OFFLINE));

        Run run = runOnce();

        assertEquals(1, run.malformedCount());
        assertEquals(1, controller.replacedCount(),
                "one bad producer must not stop the fleet being recovered");
    }

    /**
     * Runs the loop for exactly one delivering poll.
     *
     * <p>Two scheduled tasks, not one: MockConsumer runs a task at the *start*
     * of a poll, so stopping on the first would wake the loop before it had
     * ever returned a record. The second task also samples the committed
     * offset, because {@code run()} closes the consumer on its way out and a
     * closed MockConsumer refuses to be asked.
     */
    private Run runOnce() {
        FailureConsumer consumer = new FailureConsumer(kafka, controller, publisher,
                Duration.ofMillis(10));
        Run result = new Run(consumer);
        kafka.schedulePollTask(() -> { });
        kafka.schedulePollTask(() -> {
            var offsets = kafka.committed(java.util.Set.of(PARTITION));
            var committed = offsets == null ? null : offsets.get(PARTITION);
            result.committedOffset = committed == null ? -1L : committed.offset();
            consumer.stop();
        });
        consumer.run();
        return result;
    }

    /** What one run of the loop did, sampled before the consumer closed. */
    private static final class Run {
        private final FailureConsumer consumer;
        private long committedOffset = -1L;

        private Run(FailureConsumer consumer) {
            this.consumer = consumer;
        }

        long malformedCount() {
            return consumer.malformedCount();
        }

        long ignoredCount() {
            return consumer.ignoredCount();
        }
    }

    private void offer(byte[] value) {
        kafka.addRecord(new ConsumerRecord<>(KafkaTopics.DEVICE_FAILURES, 0, offset++,
                "device-002", value));
    }

    private static byte[] event(String deviceId, DeviceEventType type) {
        try {
            return new DeviceEventCodec().encode(new DeviceEventRecord(deviceId, type,
                    DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE, DETECTED_AT, 4, -1L));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String podManifest() {
        return """
                {"apiVersion":"v1","kind":"Pod",
                 "metadata":{"name":"edge-device-002","namespace":"fleet",
                   "labels":{"app":"edge-device","device-id":"device-002",
                             "fleet-id":"fleet-local"}},
                 "spec":{"restartPolicy":"Never",
                   "containers":[{"name":"device","image":"fleet/edge-device:0.1.0",
                     "env":[{"name":"FLEET_DEVICE_INDEX_OFFSET","value":"1"}]}]},
                 "status":{"phase":"Failed"}}
                """;
    }
}
