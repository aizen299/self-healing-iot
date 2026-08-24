package io.fleet.recovery;

import io.fleet.recovery.k8s.HttpKubernetesApi;
import io.fleet.recovery.k8s.KubernetesApi;
import io.fleet.recovery.k8s.KubernetesException;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the recovery operator until interrupted, or for a fixed duration.
 *
 * <p>Consumes {@code device.failures}, provisions a replacement pod for each
 * failed device, and announces the result on {@code device.recovery}. This is
 * the last arrow in the loop the project is built around: {@code device →
 * telemetry → monitoring → failure detected → recovery event → controller →
 * replacement workload → healthy fleet restored}.
 */
public final class Main {

    private static final long SHUTDOWN_GRACE_SECONDS = 15L;

    public static void main(String[] args) throws Exception {
        OperatorConfig config = OperatorConfig.fromEnv();
        printHeader(config);

        KubernetesApi kubernetes = HttpKubernetesApi.inCluster(
                config.namespace(), Duration.ofSeconds(config.apiTimeoutSeconds()));

        // Fail fast on the one permission that can be checked without side
        // effects. Deliberately not described as verifying RBAC: it exercises
        // `list` and nothing else, so a Role missing `create` or `delete`
        // starts cleanly here and fails at the first real recovery, with a
        // device already down. Checking those two properly would mean creating
        // and deleting a pod at startup, which is a worse trade than saying
        // plainly that they are unverified.
        verifyAccess(kubernetes, config);

        RecoveryController controller = new RecoveryController(
                kubernetes, new ReplacementFactory(), config, Clock.systemUTC());

        CountDownLatch finished = new CountDownLatch(1);

        // The publisher builds its producer lazily and the consumer builds its
        // consumer eagerly, and that asymmetry is deliberate. The consumer is
        // the operator's trigger: without it there is nothing to do, so failing
        // here and letting the Deployment restart is both correct and visible
        // as CrashLoopBackOff. The publisher is only the announcement, and a
        // device must still be recovered when there is nobody to tell — so a
        // producer it cannot build must not take the consumer down with it,
        // which is exactly what it used to do.
        try (RecoveryPublisher publisher = new RecoveryPublisher(config);
             FailureConsumer consumer = new FailureConsumer(config, controller, publisher)) {

            Thread poller = new Thread(consumer::run, "recovery-consumer");
            poller.start();

            // The hook stops the loop and then waits for main to finish, as
            // the gateway's does. Counting down and returning would let the
            // JVM carry on exiting and kill main part-way through its last
            // recovery — and since this process runs until interrupted, SIGTERM
            // is its normal exit path rather than an edge case.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                consumer.stop();
                try {
                    finished.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "operator-shutdown"));

            if (config.runDurationSeconds() > 0) {
                poller.join(TimeUnit.SECONDS.toMillis(config.runDurationSeconds()));
                consumer.stop();
                poller.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_GRACE_SECONDS));
            } else {
                // Until interrupted means until interrupted. Joining with the
                // shutdown grace here instead would have the operator print its
                // summary and exit fifteen seconds after starting, with the
                // fleet unwatched and the pod reported Completed.
                poller.join();
            }

            printSummary(controller, consumer, publisher);
        } finally {
            finished.countDown();
        }
    }

    private static void verifyAccess(KubernetesApi kubernetes, OperatorConfig config)
            throws KubernetesException {
        int found = kubernetes.listPods("app", config.deviceAppLabel()).size();
        System.out.printf(Locale.ROOT,
                "kubernetes reachable: %d device pod(s) in %s (list verified; create and"
                        + " delete are not exercised until a real failure)%n",
                found, config.namespace());
    }

    private static void printHeader(OperatorConfig config) {
        System.out.printf(Locale.ROOT, """
                === recovery operator (Phase 9) ===
                kafka             : %s
                consumer group    : %s
                namespace         : %s
                device app label  : %s
                device id prefix  : %s
                replace live pods : %s
                run duration      : %s
                jvm               : %s %s
                %n""",
                config.bootstrapServers(),
                config.consumerGroupId(),
                config.namespace(),
                config.deviceAppLabel(),
                config.deviceIdPrefix(),
                config.replaceLiveDevices(),
                config.runDurationSeconds() == 0
                        ? "until interrupted" : config.runDurationSeconds() + " s",
                System.getProperty("java.vm.name"),
                System.getProperty("java.version"));
    }

    private static void printSummary(RecoveryController controller, FailureConsumer consumer,
            RecoveryPublisher publisher) {
        System.out.printf(Locale.ROOT, """
                %n=== operator summary ===
                devices replaced   : %d
                already recovered  : %d
                recovery not needed: %d
                recoveries failed  : %d
                malformed events   : %d
                ignored events     : %d
                commit failures    : %d
                publish failures   : %d
                %n""",
                controller.replacedCount(),
                controller.duplicateCount(),
                controller.notNeededCount(),
                controller.failedCount(),
                consumer.malformedCount(),
                consumer.ignoredCount(),
                consumer.commitFailureCount(),
                publisher.publishFailures());

        controller.ledger().values().forEach(recovery ->
                System.out.printf(Locale.ROOT, "  %s -> %s (%s, %d ms)%n",
                        recovery.deviceId(), recovery.pod(),
                        recovery.outcome(), recovery.durationMillis()));

        System.out.println("These figures are a demonstration. Only runs recorded under "
                + "experiments/results/ count as results.");
    }

    private Main() {
    }
}
