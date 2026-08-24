package io.fleet.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
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
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Announces what the operator did, on {@code device.recovery}.
 *
 * <p>Every outcome is published, not only the successes. A recovery that was
 * refused by the cluster, or that turned out not to be needed, is exactly as
 * interesting to the experiment as one that worked — a topic carrying only
 * good news cannot support a recovery-success-rate figure.
 *
 * <p><b>{@code device.recovery} carries two kinds of record</b>, and a
 * consumer must tell them apart before reading either. The gateway publishes
 * a {@code DEVICE_RECOVERED} health transition when a replacement is
 * confirmed heartbeating; this class publishes what the controller did about
 * a failure. They describe the same recovery from opposite ends and have
 * nothing else in common.
 *
 * <p>The discriminator is the {@code kind} field, written first and present
 * only on these records: {@code kind=recovery-action} is a controller
 * action, and anything without it is a gateway health event carrying an
 * {@code event} field. Leaving a consumer to guess from which fields happened
 * to be present would work until either format gained a field.
 *
 * <p><b>Neither duration is MTTR on its own, and they must never be added.</b>
 * {@code detectionToReplacementMillis} here ends when the API server accepts
 * the pod. The gateway's {@code recoveryDurationMillis} ends when the
 * replacement's heartbeats are confirmed — it starts at the same instant and
 * already contains this one. MTTR is the gateway's number.
 */
public final class RecoveryPublisher implements AutoCloseable {

    private static final int CLOSE_TIMEOUT_SECONDS = 10;

    /** Marks a record on {@code device.recovery} as a controller action. */
    public static final String RECORD_KIND = "recovery-action";

    private final Producer<String, byte[]> producer;
    private final JsonFactory json = new JsonFactory();
    private final LongAdder failures = new LongAdder();

    public RecoveryPublisher(OperatorConfig config) {
        this(buildProducer(config));
    }

    /** Injectable producer, so the encoding can be tested with a MockProducer. */
    RecoveryPublisher(Producer<String, byte[]> producer) {
        this.producer = producer;
    }

    private static Producer<String, byte[]> buildProducer(OperatorConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "fleet-recovery-operator");
        // acks=1 rather than all: there is one broker, so all and 1 mean the
        // same thing here, and 1 says what is actually being relied on.
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Bounded so a recovery is never held up by an unreachable Kafka. The
        // announcement is downstream of the recovery, which has already
        // happened by the time this is called.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        return new KafkaProducer<>(props);
    }

    /** Never throws: failing to announce a recovery must not undo one. */
    public void publish(Recovery recovery) {
        byte[] value;
        try {
            value = encode(recovery);
        } catch (IOException e) {
            failures.increment();
            System.err.println("could not encode the recovery of " + recovery.deviceId()
                    + ": " + e.getMessage());
            return;
        }
        try {
            producer.send(new ProducerRecord<>(KafkaTopics.DEVICE_RECOVERY,
                    recovery.deviceId(), value), (metadata, exception) -> {
                        if (exception != null) {
                            failures.increment();
                            System.err.println("publishing the recovery of "
                                    + recovery.deviceId() + " failed: " + exception.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            failures.increment();
            System.err.println("publishing the recovery of " + recovery.deviceId()
                    + " was rejected: " + e.getMessage());
        }
    }

    byte[] encode(Recovery recovery) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            // First, and the reason is in the class comment: this topic has
            // two producers with unrelated schemas, and a consumer has to be
            // able to switch on something rather than sniff for fields.
            generator.writeStringField("kind", RECORD_KIND);
            generator.writeStringField("deviceId", recovery.deviceId());
            generator.writeStringField("recoveryId", recovery.recoveryId());
            generator.writeStringField("outcome", recovery.outcome().name());
            // Two names for one value, because it is two different things. A
            // consumer counting replacements must not be handed the pod that
            // made a replacement unnecessary under a field called
            // "replacementPod".
            if (recovery.outcome() == RecoveryOutcome.NOT_NEEDED) {
                generator.writeNullField("replacementPod");
                generator.writeStringField("existingPod", recovery.pod());
            } else {
                generator.writeStringField("replacementPod", recovery.pod());
            }
            generator.writeNumberField("detectedAt", recovery.detectedAtMillis());
            generator.writeNumberField("actedAt", recovery.actedAtMillis());
            // Named for what it measures rather than "mttr", which it is not:
            // it ends when the API server accepts the pod, not when the device
            // is publishing again.
            //
            // Null unless the number means something. For an outcome that
            // replaced nothing the subtraction spans a failure this recovery
            // did not answer — a duplicate arriving thirty seconds later
            // reported a 29-second "replacement" — and a negative result means
            // the gateway's clock and the operator's disagree. Anything
            // averaging this field across the topic would be wrong in both
            // cases.
            if (recovery.hasMeaningfulDuration()) {
                generator.writeNumberField("detectionToReplacementMillis",
                        recovery.durationMillis());
            } else {
                generator.writeNullField("detectionToReplacementMillis");
            }
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    public long publishFailures() {
        return failures.sum();
    }

    @Override
    public void close() {
        producer.close(java.time.Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS));
    }
}
