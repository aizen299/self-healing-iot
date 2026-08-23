package io.fleet.gateway;

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

        printHeader(config);

        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        try (MqttIngestor ingestor = new MqttIngestor(config, registry, metrics);
             HealthMonitor monitor = new HealthMonitor(registry, config.healthPolicy(), metrics,
                     ingestor, config.monitorIntervalMillis());
             HealthApi api = new HealthApi(config, registry, metrics)) {

            // Closes the cycle described on MqttIngestor.onTransition: the
            // monitor announces what the ingestor observes, and publishes
            // through the ingestor's connection.
            ingestor.onTransition(monitor::announce);

            ingestor.start();
            monitor.start();
            api.start();
            System.out.printf(Locale.ROOT, "health API on http://%s:%d/health%n",
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

            printSummary(registry, metrics);
        } finally {
            finished.countDown();
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

    private static void printSummary(DeviceRegistry registry, GatewayMetrics metrics) {
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
                + "experiments/results/ count as results.");
    }

    private Main() {
    }
}
