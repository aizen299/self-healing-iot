package io.fleet.recovery;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the operator says it did, on {@code device.recovery}.
 *
 * <p>Covered only incidentally through {@code FailureConsumerTest} before,
 * which left the encoder's branches — and the whole announcement-is-optional
 * contract — resting on tests that were looking at something else.
 */
class RecoveryPublisherTest {

    private static final long DETECTED_AT = 1_787_591_224_927L;

    private static MockProducer<String, byte[]> producer() {
        return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    private static String bodyOf(MockProducer<String, byte[]> producer) {
        return new String(producer.history().get(0).value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a replacement is announced with its pod and its duration")
    void announcesAReplacement() {
        MockProducer<String, byte[]> producer = producer();
        try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
            publisher.publish(new Recovery("device-003", "49f05eca57",
                    "device-003-r-49f05eca57", DETECTED_AT, DETECTED_AT + 62,
                    RecoveryOutcome.REPLACED));
        }

        ProducerRecord<String, byte[]> sent = producer.history().get(0);
        assertEquals(KafkaTopics.DEVICE_RECOVERY, sent.topic());
        assertEquals("device-003", sent.key());

        String body = bodyOf(producer);
        assertTrue(body.contains("\"replacementPod\":\"device-003-r-49f05eca57\""), body);
        assertTrue(body.contains("\"detectionToReplacementMillis\":62"), body);
    }

    @Test
    @DisplayName("the kind discriminator comes first, before anything else")
    void kindIsTheFirstField() {
        // device.recovery has two producers with unrelated schemas — this one
        // and the gateway's DEVICE_RECOVERED health transition. A consumer has
        // to be able to switch on a field rather than sniff for one, and a
        // streaming parser should be able to stop at the first key.
        MockProducer<String, byte[]> producer = producer();
        try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
            publisher.publish(new Recovery("device-003", "abc", "device-003-r-abc",
                    DETECTED_AT, DETECTED_AT + 5, RecoveryOutcome.REPLACED));
        }

        assertTrue(bodyOf(producer).startsWith("{\"kind\":\"recovery-action\""),
                bodyOf(producer));
    }

    @Test
    @DisplayName("a device found alive reports an existing pod, not a replacement")
    void doesNotCallALivePodAReplacement() {
        // Anyone counting replacements off this topic must not be handed the
        // pod that made a replacement unnecessary under the name
        // "replacementPod".
        MockProducer<String, byte[]> producer = producer();
        try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
            publisher.publish(new Recovery("device-003", "abc", "edge-device-003",
                    DETECTED_AT, DETECTED_AT + 40, RecoveryOutcome.NOT_NEEDED));
        }

        String body = bodyOf(producer);
        assertTrue(body.contains("\"replacementPod\":null"), body);
        assertTrue(body.contains("\"existingPod\":\"edge-device-003\""), body);
    }

    @Test
    @DisplayName("an outcome that replaced nothing carries no duration")
    void refusesToTimeSomethingItDidNotDo() {
        // A redelivered event arriving thirty seconds later once reported a
        // 29-second "replacement". The subtraction spans a failure this
        // recovery did not answer, so averaging the field would be wrong.
        for (RecoveryOutcome outcome : new RecoveryOutcome[] {
                RecoveryOutcome.ALREADY_RECOVERED, RecoveryOutcome.NOT_NEEDED,
                RecoveryOutcome.FAILED }) {
            MockProducer<String, byte[]> producer = producer();
            try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
                publisher.publish(new Recovery("device-003", "abc", "device-003-r-abc",
                        DETECTED_AT, DETECTED_AT + 29_420, outcome));
            }

            String body = bodyOf(producer);
            assertTrue(body.contains("\"detectionToReplacementMillis\":null"),
                    outcome + ": " + body);
            assertFalse(body.contains("29420"), outcome + ": " + body);
        }
    }

    @Test
    @DisplayName("a negative duration is withheld rather than published")
    void withholdsAnImpossibleDuration() {
        // The two ends are read from two pods' clocks. A gateway running a few
        // milliseconds ahead of the operator turns a fast recovery negative.
        MockProducer<String, byte[]> producer = producer();
        try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
            publisher.publish(new Recovery("device-003", "abc", "device-003-r-abc",
                    DETECTED_AT, DETECTED_AT - 12, RecoveryOutcome.REPLACED));
        }

        assertTrue(bodyOf(producer).contains("\"detectionToReplacementMillis\":null"),
                bodyOf(producer));
    }

    @Test
    @DisplayName("every outcome is published, not only the successes")
    void announcesFailuresToo() {
        // A topic carrying only good news cannot support a recovery-success
        // rate, which is one of Pillar B's two numbers.
        MockProducer<String, byte[]> producer = producer();
        try (RecoveryPublisher publisher = new RecoveryPublisher(producer)) {
            publisher.publish(new Recovery("device-003", "abc", null,
                    DETECTED_AT, DETECTED_AT + 900, RecoveryOutcome.FAILED));
        }

        assertEquals(1, producer.history().size());
        assertTrue(bodyOf(producer).contains("\"outcome\":\"FAILED\""), bodyOf(producer));
    }

    @Test
    @DisplayName("an unreachable Kafka is counted, never thrown")
    void survivesAProducerItCannotBuild() {
        // The announcement is downstream of the recovery, which has already
        // happened by the time this is called. Before the producer was built
        // lazily this case did not reach publish() at all — it took the whole
        // operator down at startup, so the announcement path stopped the
        // recovery path.
        LazyResource<Producer<String, byte[]>> unavailable = new LazyResource<>(
                "a test producer",
                () -> {
                    throw new IllegalStateException("no resolvable bootstrap urls");
                },
                open -> open.close(Duration.ofSeconds(1)), 10_000L, Clock.systemUTC());

        try (RecoveryPublisher publisher = new RecoveryPublisher(unavailable)) {
            publisher.publish(new Recovery("device-003", "abc", "device-003-r-abc",
                    DETECTED_AT, DETECTED_AT + 62, RecoveryOutcome.REPLACED));

            assertEquals(1L, publisher.publishFailures());
        }
    }
}
