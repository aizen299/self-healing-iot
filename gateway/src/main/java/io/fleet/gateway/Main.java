package io.fleet.gateway;

import io.fleet.common.StoreException;
import io.fleet.common.TelemetryStore;
import io.fleet.gateway.store.H2TelemetryStore;
import io.fleet.gateway.store.NoOpTelemetryStore;
import io.fleet.gateway.store.StoreConfig;
import io.fleet.gateway.store.StoreMaintainer;
import io.fleet.gateway.kafka.ForwarderConfig;
import io.fleet.gateway.kafka.KafkaTelemetryForwarder;
import io.fleet.gateway.kafka.NoOpForwarder;
import io.fleet.gateway.kafka.TelemetryForwarder;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the gateway until interrupted, or for a fixed duration.
 *
 * <p>Ingests telemetry and presence from the broker and serves the health
 * API. Heartbeat timeouts and the device state machine arrive in Phase 4;
 * forwarding to Kafka in Phase 6.
 */
public final class Main {

    /** How long the shutdown hook waits for an orderly exit before giving up. */
    private static final long SHUTDOWN_GRACE_SECONDS = 10L;

    public static void main(String[] args) throws Exception {
        GatewayConfig config = GatewayConfig.fromEnv();
        DeviceRegistry registry = new DeviceRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        // One policy object, so the detector's rules have a single source.
        HealthPolicy policy = config.healthPolicy();
        StoreConfig storeConfig = StoreConfig.fromEnv();
        ForwarderConfig kafkaConfig = ForwarderConfig.fromEnv();

        printHeader(config);

        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        try (TelemetryForwarder forwarder = openForwarder(kafkaConfig);
             TelemetryStore store = openStore(storeConfig);
             StoreMaintainer maintenance = new StoreMaintainer(store, storeConfig);
             MqttIngestor ingestor = new MqttIngestor(config, registry, metrics, policy,
                     store, forwarder, java.time.Clock.systemUTC());
             HealthMonitor monitor = new HealthMonitor(registry, policy, metrics,
                     ingestor, store, forwarder, config.monitorIntervalMillis());
             // The ingestor supplies readiness: /ready is 503 until the
             // broker connection is up, so a Kubernetes Service does not
             // route to a gateway that is running but recording nothing.
             // The drop count is the only thing HealthApi cannot reach on its
             // own; it lives on the forwarder, and this is the only place that
             // has both. Everything else the exporter needs, HealthApi already
             // holds — including the readiness supplier, so /ready and
             // /metrics cannot disagree about the broker.
             HealthApi api = new HealthApi(config, registry, metrics, store,
                     ingestor::isConnected, forwarder::forwardFailures)) {

            // Closes the cycle described on MqttIngestor.onTransition: the
            // monitor announces what the ingestor observes, and publishes
            // through the ingestor's connection.
            ingestor.onTransition(monitor::announce);

            ingestor.start();
            monitor.start();
            maintenance.start();
            api.start();
            System.out.printf(Locale.ROOT, "health API on http://%s:%d/health (readiness"
                            + " at /ready, metrics at /metrics)%n",
                    config.httpHost(), api.port());

            // The hook waits for the main thread to finish shutting down.
            // Counting the latch down and returning would let the JVM continue
            // its exit sequence and kill main part-way through disconnecting or
            // printing — and since this process runs until interrupted, SIGINT
            // is its normal exit path rather than an edge case.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stopped.countDown();
                try {
                    finished.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "gateway-shutdown"));

            if (config.runDurationSeconds() > 0) {
                stopped.await(config.runDurationSeconds(), TimeUnit.SECONDS);
            } else {
                stopped.await();
            }

            printSummary(registry, metrics, store, forwarder);
        } finally {
            finished.countDown();
        }
    }

    /**
     * Opens the configured store, falling back to a null object when
     * persistence is off or unavailable.
     *
     * <p>The gateway detects failures with or without a store, so storage
     * being unavailable must not stop it starting — but the operator has to
     * know the history is not being kept.
     */
    /**
     * Opens the Kafka forwarder, or a null object when it is off.
     *
     * <p>Off by default, and never fatal when on: the gateway ingests,
     * detects, and persists without Kafka, so an unreachable broker must not
     * stop any of that. Kafka is a downstream copy, not the system of record.
     *
     * <p>No try/catch around the forwarder any more, and that is the point of
     * the change rather than an omission. This used to catch a failed producer
     * construction and substitute the null object, which made an unreachable
     * broker at startup permanent — the gateway forwarded nothing for the life
     * of the process even once Kafka was up. Construction no longer touches
     * the network: the producer is built on the forwarder's own sender thread
     * and retried there until it works, so there is nothing here left to fail
     * that is not a programming error.
     */
    private static TelemetryForwarder openForwarder(ForwarderConfig kafkaConfig) {
        if (!kafkaConfig.enabled()) {
            System.out.println("kafka forwarding disabled");
            return new NoOpForwarder();
        }
        System.out.println("forwarding to kafka at " + kafkaConfig.bootstrapServers());
        return new KafkaTelemetryForwarder(kafkaConfig);
    }

    private static TelemetryStore openStore(StoreConfig storeConfig) {
        if (!storeConfig.enabled()) {
            System.out.println("telemetry store disabled; history will not be kept");
            return new NoOpTelemetryStore();
        }
        try {
            H2TelemetryStore store = new H2TelemetryStore(storeConfig);
            System.out.println("telemetry store at " + store.jdbcUrl());
            return store;
        } catch (StoreException e) {
            // Consistent with the write path. A store that fails on every
            // write is tolerated because losing detection is worse than losing
            // history; a store that cannot be opened at all was previously
            // fatal, which meant a stale lock file or a full disk stopped the
            // fleet being monitored for exactly the reason the design says
            // should never stop monitoring.
            System.err.println("could not open the telemetry store, continuing without history: "
                    + e.getMessage());
            return new NoOpTelemetryStore();
        }
    }

    private static void printHeader(GatewayConfig config) {
        System.out.printf(Locale.ROOT, """
                === gateway (Phase 3) ===
                broker            : %s
                client id         : %s
                subscription QoS  : %d
                heartbeat expected: every %d ms
                suspect / offline : %d / %d missed heartbeats
                recovery confirms : %d heartbeats
                run duration      : %s
                jvm               : %s %s
                %n""",
                config.brokerUrl(),
                config.clientId(),
                config.subscriptionQos(),
                config.heartbeatIntervalMillis(),
                config.suspectAfterMisses(),
                config.offlineAfterMisses(),
                config.recoveryConfirmations(),
                config.runDurationSeconds() == 0
                        ? "until interrupted" : config.runDurationSeconds() + " s",
                System.getProperty("java.vm.name"),
                System.getProperty("java.version"));
    }

    private static void printSummary(DeviceRegistry registry, GatewayMetrics metrics,
            TelemetryStore store, TelemetryForwarder forwarder) {
        // Printed alongside the figures, not buried in a log line: a run that
        // lost readings must be hard to mistake for one that did not.
        long dropped = store.droppedWrites();
        String historyState = dropped == 0L
                ? "complete"
                : "INCOMPLETE — " + dropped + " readings lost; not a valid experiment record";
        System.out.printf(Locale.ROOT, """
                %n=== gateway summary ===
                devices known     : %d
                devices reporting : %d
                devices online    : %d
                health            : %s
                heartbeats accepted: %d (malformed %d)
                failures detected : %d
                recoveries        : %d (mean %d ms)
                monitor errors    : %d
                store errors      : %d
                kafka failures    : %d
                history           : %s
                telemetry accepted: %d
                telemetry rejected: %d (malformed %d, invalid %d)
                presence events   : %d
                unroutable msgs   : %d
                invalid presence  : %d
                handler errors    : %d
                connection losses : %d
                %n""",
                registry.size(),
                registry.reportingCount(),
                registry.onlineCount(),
                registry.healthCounts(),
                metrics.heartbeatsAcceptedCount(),
                metrics.heartbeatsMalformedCount(),
                metrics.failuresDetectedCount(),
                metrics.recoveriesObservedCount(),
                metrics.meanRecoveryMillis(),
                metrics.monitorErrorCount(),
                metrics.storeErrorCount(),
                forwarder.forwardFailures(),
                historyState,
                metrics.acceptedCount(),
                metrics.rejectedCount(),
                metrics.malformedCount(),
                metrics.invalidCount(),
                metrics.presenceCount(),
                metrics.unroutableCount(),
                metrics.invalidPresenceCount(),
                metrics.handlerErrorCount(),
                metrics.connectionLossCount());

        System.out.println("These figures are a demonstration. Only runs recorded under "
                + "experiments/results/ count as results" 
                + (dropped == 0L ? "." : ", and this run's history is incomplete."));
    }

    private Main() {
    }
}
