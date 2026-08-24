package io.fleet.recovery;

import io.fleet.common.DeviceEventCodec;
import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.KafkaTopics;
import io.fleet.common.MalformedPayloadException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reads {@code device.failures} and hands each failure to the controller.
 *
 * <p>Subscribed to the failures topic alone, not to {@code device.events}.
 * ADR-009 split them so this consumer cannot see a transition it should not
 * act on — filtering a firehose would work until someone changed the filter.
 *
 * <p>Offsets are committed **after** the controller has acted, never before.
 * Committing first would turn an operator crash mid-recovery into a failure
 * nobody ever handles: the event would be marked consumed and the device
 * would stay dead. Committing after means a crash redelivers, which is safe
 * precisely because the replacement's name is derived from the failure
 * (ADR-011) and the second attempt creates nothing.
 */
public final class FailureConsumer implements AutoCloseable {

    private final Consumer<String, byte[]> consumer;
    private final RecoveryController controller;
    private final RecoveryPublisher publisher;
    private final DeviceEventCodec codec = new DeviceEventCodec();
    private final Duration pollTimeout;

    private final LongAdder malformed = new LongAdder();
    private final LongAdder ignored = new LongAdder();
    private final LongAdder commitFailures = new LongAdder();

    private volatile boolean running = true;

    public FailureConsumer(OperatorConfig config, RecoveryController controller,
            RecoveryPublisher publisher) {
        this(buildConsumer(config), controller, publisher,
                Duration.ofMillis(config.pollTimeoutMillis()));
        consumer.subscribe(List.of(KafkaTopics.DEVICE_FAILURES));
    }

    /** Injectable consumer, so the loop can be driven by a {@code MockConsumer}. */
    FailureConsumer(Consumer<String, byte[]> consumer, RecoveryController controller,
            RecoveryPublisher publisher, Duration pollTimeout) {
        this.consumer = consumer;
        this.controller = controller;
        this.publisher = publisher;
        this.pollTimeout = pollTimeout;
    }

    private static Consumer<String, byte[]> buildConsumer(OperatorConfig config) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        // Manual, because the commit has to happen after the recovery, not
        // after the poll — see the class comment.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // A brand-new operator joining an existing cluster should not replay
        // every failure the fleet has ever had and try to recover devices that
        // recovered days ago. Those attempts would mostly land on NOT_NEEDED,
        // but "mostly" is not a guarantee worth relying on.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return new KafkaConsumer<>(props);
    }

    /** Polls until {@link #stop()}. */
    public void run() {
        try {
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
                for (ConsumerRecord<String, byte[]> record : records) {
                    handle(record);
                }
                if (!records.isEmpty()) {
                    commit();
                }
            }
        } catch (WakeupException e) {
            // The documented way to break out of poll() from another thread.
            if (running) {
                throw e;
            }
        } finally {
            consumer.close();
        }
    }

    /**
     * Commits, and survives failing to.
     *
     * <p>Letting a commit exception escape kills the poll loop and, with it,
     * the process — a rebalance throws {@code CommitFailedException} and a
     * broker blip throws a retriable one, neither of which is a reason to stop
     * recovering devices. What an uncommitted offset actually causes is
     * redelivery, which is safe here by construction: the replacement's name
     * is derived from the failure (ADR-011), so the second attempt creates
     * nothing.
     */
    private void commit() {
        try {
            consumer.commitSync();
        } catch (RebalanceInProgressException | RetriableException e) {
            commitFailures.increment();
            System.err.println("offset commit failed, continuing; these events will be"
                    + " redelivered and are idempotent: " + e.getMessage());
        } catch (CommitFailedException e) {
            commitFailures.increment();
            System.err.println("offset commit rejected — the group rebalanced while this"
                    + " batch was being handled; the events will be redelivered: "
                    + e.getMessage());
        }
    }

    private void handle(ConsumerRecord<String, byte[]> record) {
        DeviceEventRecord event;
        try {
            event = codec.decode(record.value());
        } catch (MalformedPayloadException e) {
            // Counted and dropped, never fatal. One malformed record must not
            // stop the operator recovering the rest of the fleet, and it would
            // be redelivered for ever if the offset were not committed.
            malformed.increment();
            System.err.println("dropped a malformed record from " + KafkaTopics.DEVICE_FAILURES
                    + ": " + e.getMessage());
            return;
        }

        // device.failures should only ever carry DEVICE_OFFLINE. Checked
        // anyway: this operator deletes pods, and "the topic is supposed to
        // only contain X" is not a safe basis for that.
        if (event.event() != DeviceEventType.DEVICE_OFFLINE) {
            ignored.increment();
            System.err.println("ignoring a " + event.event() + " on "
                    + KafkaTopics.DEVICE_FAILURES + " for " + event.deviceId()
                    + "; this operator acts only on DEVICE_OFFLINE");
            return;
        }

        Recovery recovery = controller.onFailure(event);
        publisher.publish(recovery);
    }

    /** Breaks the poll loop; safe to call from a shutdown hook. */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    public long malformedCount() {
        return malformed.sum();
    }

    public long ignoredCount() {
        return ignored.sum();
    }

    /** Commits that failed and were tolerated; each one means a redelivery. */
    public long commitFailureCount() {
        return commitFailures.sum();
    }

    @Override
    public void close() {
        stop();
    }
}
