package io.fleet.gateway;

import io.fleet.common.DeviceHealth;
import io.fleet.common.PrometheusText;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Renders what the gateway already counts, in Prometheus exposition format.
 *
 * <p>Deliberately a reader and not a registry. Every number here is read from
 * {@link GatewayMetrics}, {@link DeviceRegistry} or the JVM at scrape time —
 * nothing is incremented in this class and nothing is stored. Metrics that
 * kept their own copy would be a second place a count could be defined, and
 * the two would drift.
 *
 * <p>Scraped through the {@code gateway-admin} Service rather than
 * {@code gateway}. Readiness withdraws the pod from the latter while the
 * broker connection is down, which is exactly the outage a dashboard is
 * wanted for: the metrics have to keep answering when the gateway has stopped
 * being routable (ADR-012).
 *
 * <p>Nothing here is a result. These are live operational values; only a run
 * recorded under {@code experiments/} supports a reported number.
 */
public final class MetricsExporter {

    private final DeviceRegistry registry;
    private final GatewayMetrics metrics;
    private final BooleanSupplier brokerConnected;
    private final LongSupplier kafkaForwardFailures;

    public MetricsExporter(DeviceRegistry registry, GatewayMetrics metrics,
            BooleanSupplier brokerConnected, LongSupplier kafkaForwardFailures) {
        this.registry = registry;
        this.metrics = metrics;
        this.brokerConnected = brokerConnected;
        this.kafkaForwardFailures = kafkaForwardFailures;
    }

    /** One scrape's worth of text. */
    public String render() {
        PrometheusText out = new PrometheusText();
        fleet(out);
        ingestion(out);
        recovery(out);
        gateway(out);
        jvm(out);
        return out.render();
    }

    private void fleet(PrometheusText out) {
        // Two counts, because they answer different questions and the
        // difference has bitten before. A retained presence message from a
        // device that no longer exists creates a record the gateway has never
        // heard from; it is known and it is not reporting. A dashboard panel
        // built on the first number counts ghosts.
        out.gauge("fleet_devices_known",
                "Device ids the gateway has a record for, including"
                        + " retained-presence ghosts it has never heard from");
        out.sample("fleet_devices_known", registry.size());

        out.gauge("fleet_devices_reporting",
                "Devices that have actually sent the gateway something");
        out.sample("fleet_devices_reporting", registry.reportingCount());

        out.gauge("fleet_devices", "Devices by health state");
        Map<DeviceHealth, Long> byHealth = registry.healthCounts();
        // Every state, including the zeroes: a panel for OFFLINE that simply
        // has no series when nothing is offline reads as "no data", which
        // looks like a broken scrape rather than a healthy fleet.
        for (DeviceHealth health : DeviceHealth.values()) {
            out.sample("fleet_devices", "state", health.name(), byHealth.get(health));
        }

        out.gauge("fleet_device_up", "1 when the device is ONLINE, 0 otherwise");
        for (DeviceRecord device : registry.all()) {
            out.sample("fleet_device_up", "device_id", device.deviceId(),
                    device.health() == DeviceHealth.ONLINE ? 1L : 0L);
        }
    }

    private void ingestion(PrometheusText out) {
        out.counter("fleet_telemetry_accepted_total", "Readings accepted");
        out.sample("fleet_telemetry_accepted_total", metrics.acceptedCount());

        // Split by cause, as GatewayMetrics splits them: a payload the gateway
        // cannot parse and a reading whose values are impossible point at
        // different faults.
        out.counter("fleet_telemetry_rejected_total", "Readings rejected, by cause");
        out.sample("fleet_telemetry_rejected_total", "reason", "malformed",
                metrics.malformedCount());
        out.sample("fleet_telemetry_rejected_total", "reason", "invalid",
                metrics.invalidCount());

        out.counter("fleet_heartbeats_accepted_total", "Heartbeats accepted");
        out.sample("fleet_heartbeats_accepted_total", metrics.heartbeatsAcceptedCount());

        out.counter("fleet_heartbeats_malformed_total", "Heartbeats that would not parse");
        out.sample("fleet_heartbeats_malformed_total", metrics.heartbeatsMalformedCount());

        out.counter("fleet_presence_events_total", "Status messages read as a presence change");
        out.sample("fleet_presence_events_total", metrics.presenceCount());

        out.counter("fleet_presence_invalid_total",
                "Status messages from our own devices that were not a presence value");
        out.sample("fleet_presence_invalid_total", metrics.invalidPresenceCount());

        out.counter("fleet_messages_unroutable_total",
                "Messages on a subscribed topic the gateway cannot interpret");
        out.sample("fleet_messages_unroutable_total", metrics.unroutableCount());
    }

    private void recovery(PrometheusText out) {
        out.counter("fleet_failures_detected_total", "Devices declared failed");
        out.sample("fleet_failures_detected_total", metrics.failuresDetectedCount());

        out.counter("fleet_recoveries_observed_total",
                "Failed devices confirmed back in service");
        out.sample("fleet_recoveries_observed_total", metrics.recoveriesObservedCount());

        // Detection to confirmed heartbeats — the gateway's number, which is
        // the one MTTR means. The operator's detectionToReplacementMillis ends
        // when the API server accepts the pod and is already contained in
        // this; the two must never be added.
        out.histogram("fleet_recovery_duration_millis",
                "Failure detection to confirmed heartbeats, in milliseconds."
                        + " This is MTTR; the operator's own duration is a part of it"
                        + " and the two must not be added");
        long[] bounds = metrics.recoveryBucketBoundsMillis();
        long[] counts = metrics.recoveryBucketCounts();
        long cumulative = 0L;
        for (int i = 0; i < bounds.length; i++) {
            cumulative += counts[i];
            out.sample("fleet_recovery_duration_millis_bucket", "le",
                    Long.toString(bounds[i]), cumulative);
        }
        // The +Inf bucket must equal _count, so the overflow slot goes here.
        cumulative += counts[bounds.length];
        out.sample("fleet_recovery_duration_millis_bucket", "le", "+Inf", cumulative);
        out.sample("fleet_recovery_duration_millis_sum",
                metrics.recoveryDurationTotalMillis());
        // Not recoveriesObservedCount: a recovery whose two clocks disagreed
        // contributes no duration, and a _count larger than the +Inf bucket is
        // an inconsistent histogram that Prometheus reads as buckets missing.
        out.sample("fleet_recovery_duration_millis_count",
                metrics.recoveryDurationSampleCount());
    }

    private void gateway(PrometheusText out) {
        out.gauge("fleet_gateway_broker_connected", "1 when the MQTT connection is up");
        out.sample("fleet_gateway_broker_connected", brokerConnected.getAsBoolean() ? 1L : 0L);

        out.counter("fleet_gateway_connection_losses_total", "MQTT connections lost");
        out.sample("fleet_gateway_connection_losses_total", metrics.connectionLossCount());

        out.counter("fleet_gateway_handler_errors_total",
                "Errors escaping a message handler; should stay zero");
        out.sample("fleet_gateway_handler_errors_total", metrics.handlerErrorCount());

        out.counter("fleet_gateway_monitor_errors_total",
                "Health sweeps that threw; non-zero means detection stalled");
        out.sample("fleet_gateway_monitor_errors_total", metrics.monitorErrorCount());

        out.counter("fleet_gateway_event_publish_failures_total",
                "Health events the gateway could not publish over MQTT");
        out.sample("fleet_gateway_event_publish_failures_total",
                metrics.eventPublishFailureCount());

        out.counter("fleet_gateway_store_errors_total",
                "Store reads or writes that failed; history is lossy when this moves");
        out.sample("fleet_gateway_store_errors_total", metrics.storeErrorCount());

        out.counter("fleet_gateway_kafka_forward_failures_total",
                "Records dropped on the way to Kafka, including a full queue");
        out.sample("fleet_gateway_kafka_forward_failures_total",
                kafkaForwardFailures.getAsLong());
    }

    /**
     * Four numbers off the MXBeans, under the {@code fleet_} prefix like
     * everything else.
     *
     * <p>Not named {@code jvm_memory_used_bytes}: that name belongs to the
     * Micrometer and simpleclient schemas, and a dashboard importing a
     * community panel would silently get these four instead of the several
     * dozen it expects. Whatever these are called they are the gateway's
     * numbers — the constrained-versus-naive comparison is measured on the
     * device JVM by the experiment harness, not scraped from here.
     */
    private void jvm(PrometheusText out) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        out.gauge("fleet_jvm_heap_used_bytes", "Heap in use by this process");
        out.sample("fleet_jvm_heap_used_bytes", memory.getHeapMemoryUsage().getUsed());

        out.gauge("fleet_jvm_heap_max_bytes", "Heap ceiling, or -1 when undefined");
        out.sample("fleet_jvm_heap_max_bytes", memory.getHeapMemoryUsage().getMax());

        out.counter("fleet_jvm_gc_collections_total", "Collections, by collector");
        out.counter("fleet_jvm_gc_time_millis_total", "Time spent collecting, by collector");
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.sample("fleet_jvm_gc_collections_total", "gc", collector.getName(),
                    collector.getCollectionCount());
            out.sample("fleet_jvm_gc_time_millis_total", "gc", collector.getName(),
                    collector.getCollectionTime());
        }

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        out.gauge("fleet_jvm_threads", "Live threads");
        out.sample("fleet_jvm_threads", threads.getThreadCount());
    }
}
