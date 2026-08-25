package io.fleet.recovery;

import io.fleet.common.PrometheusText;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * Renders what the operator already counts, in Prometheus exposition format.
 *
 * <p>A reader, like the gateway's exporter: every number is taken from the
 * controller, the consumer or the publisher at scrape time and nothing is
 * stored here. The ledger is deliberately not walked — it grows with every
 * recovery for the life of the process, and a scrape whose cost rises with
 * uptime is a slow way to build an outage.
 *
 * <p>The operator's own duration is exposed as a summary named for what it
 * measures, not as {@code mttr}. It ends when the API server accepts the
 * replacement pod. The gateway's {@code fleet_recovery_duration_millis} starts
 * at the same instant, ends when the replacement's heartbeats are confirmed,
 * and already contains this one — <b>the two must never be added</b>, and a
 * dashboard that summed them would roughly double the reported MTTR.
 */
public final class OperatorMetricsExporter {

    private final RecoveryController controller;
    private final FailureConsumer consumer;
    private final RecoveryPublisher publisher;

    public OperatorMetricsExporter(RecoveryController controller, FailureConsumer consumer,
            RecoveryPublisher publisher) {
        this.controller = controller;
        this.consumer = consumer;
        this.publisher = publisher;
    }

    public String render() {
        PrometheusText out = new PrometheusText();

        // One counter with an outcome label rather than four names: every
        // panel that wants "how many recoveries were attempted" is then a sum
        // over the label instead of an addition someone can get wrong, and a
        // new RecoveryOutcome shows up without a dashboard edit.
        out.counter("fleet_operator_recoveries_total", "Failure events acted on, by outcome");
        out.sample("fleet_operator_recoveries_total", "outcome", "REPLACED",
                controller.replacedCount());
        out.sample("fleet_operator_recoveries_total", "outcome", "ALREADY_RECOVERED",
                controller.duplicateCount());
        out.sample("fleet_operator_recoveries_total", "outcome", "NOT_NEEDED",
                controller.notNeededCount());
        out.sample("fleet_operator_recoveries_total", "outcome", "FAILED",
                controller.failedCount());

        out.summary("fleet_operator_detection_to_replacement_millis",
                "Failure detection to the API server accepting the replacement pod."
                        + " A component of MTTR, not MTTR: the gateway's"
                        + " fleet_recovery_duration_millis already contains this and the"
                        + " two must not be added");
        out.sample("fleet_operator_detection_to_replacement_millis_sum",
                controller.replacementDurationTotalMillis());
        out.sample("fleet_operator_detection_to_replacement_millis_count",
                controller.replacementDurationSampleCount());

        out.counter("fleet_operator_events_dropped_total",
                "Records read off device.failures and not acted on, by reason");
        out.sample("fleet_operator_events_dropped_total", "reason", "malformed",
                consumer.malformedCount());
        // Should stay zero: device.failures is a strict subset of
        // device.events (ADR-009), so anything counted here means the topic is
        // carrying something the operator was never meant to see.
        out.sample("fleet_operator_events_dropped_total", "reason", "wrong_event_type",
                consumer.ignoredCount());

        out.counter("fleet_operator_commit_failures_total",
                "Offset commits that failed; each one means a redelivery, which is safe");
        out.sample("fleet_operator_commit_failures_total", consumer.commitFailureCount());

        out.counter("fleet_operator_publish_failures_total",
                "Recoveries that happened but could not be announced on device.recovery");
        out.sample("fleet_operator_publish_failures_total", publisher.publishFailures());

        // The same four names the gateway exports, so one dashboard panel
        // shows both processes side by side under a `component` label. Heap
        // max included even though this process is not what Pillar A measures:
        // a panel that draws the gateway's ceiling and not the operator's
        // reads as a missing series rather than a deliberate omission.
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        out.gauge("fleet_jvm_heap_used_bytes", "Heap in use by this process");
        out.sample("fleet_jvm_heap_used_bytes", memory.getHeapMemoryUsage().getUsed());
        out.gauge("fleet_jvm_heap_max_bytes", "Heap ceiling, or -1 when undefined");
        out.sample("fleet_jvm_heap_max_bytes", memory.getHeapMemoryUsage().getMax());
        out.gauge("fleet_jvm_threads", "Live threads");
        out.sample("fleet_jvm_threads", ManagementFactory.getThreadMXBean().getThreadCount());

        return out.render();
    }
}
