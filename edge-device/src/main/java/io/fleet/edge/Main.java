package io.fleet.edge;

import io.fleet.edge.harness.FleetHarness;
import io.fleet.edge.harness.FleetRunResult;
import io.fleet.edge.metrics.GcSnapshot;
import io.fleet.edge.sink.CountingSink;

import java.util.Locale;

/**
 * Runs a simulated fleet for a fixed duration and prints a summary.
 *
 * <p>Phase 1 has no broker: telemetry goes to a counting sink so the run
 * measures the cost of producing telemetry, not of transporting it. Phase 2
 * swaps in an MQTT sink behind {@code TelemetrySink} without changing any
 * device code.
 *
 * <p>The printed figures are a demonstration, not a result. Nothing here may
 * be quoted in documentation or the report unless it came from a run recorded
 * under {@code experiments/results/} with its full configuration attached.
 */
public final class Main {

    public static void main(String[] args) throws InterruptedException {
        DeviceConfig config = DeviceConfig.fromEnv();
        CountingSink sink = new CountingSink();

        printHeader(config);

        try (FleetHarness harness = new FleetHarness(config, sink)) {
            Runtime.getRuntime().addShutdownHook(new Thread(harness::close, "fleet-shutdown"));

            GcSnapshot before = GcSnapshot.capture();
            harness.start();
            harness.awaitRunDuration();
            FleetRunResult result = harness.stop();
            GcSnapshot delta = GcSnapshot.capture().since(before);

            printSummary(result, sink, delta);
        }
    }

    private static void printHeader(DeviceConfig config) {
        System.out.printf(Locale.ROOT, """
                === edge-device simulator (Phase 1) ===
                variant           : %s
                devices           : %d
                publish interval  : %d ms
                run duration      : %d s
                failure mode      : %s%s
                seed              : %d
                jvm               : %s %s
                max heap          : %s
                collectors        : %s
                %n""",
                config.variant(),
                config.deviceCount(),
                config.publishIntervalMillis(),
                config.runDurationSeconds(),
                config.failureMode(),
                config.failureMode() == FailureMode.NONE
                        ? "" : " (after " + config.failAfterReadings() + " readings)",
                config.seed(),
                System.getProperty("java.vm.name"),
                System.getProperty("java.version"),
                formatBytes(Runtime.getRuntime().maxMemory()),
                GcSnapshot.collectorNames());
    }

    private static void printSummary(
            FleetRunResult result, CountingSink sink, GcSnapshot delta) {
        System.out.printf(Locale.ROOT, """
                === run summary ===
                duration          : %d ms
                readings published: %d
                payloads delivered: %d
                bytes delivered   : %s
                throughput        : %.1f readings/s
                crashed devices   : %d%s
                sink errors       : %d
                unexpected errors : %d
                gc collections    : %d
                gc time           : %d ms
                heap in use       : %s
                %n""",
                result.durationMillis(),
                result.readingsPublished(),
                sink.payloadCount(),
                formatBytes(sink.byteCount()),
                result.throughputPerSecond(),
                result.crashedDevices().size(),
                result.crashedDevices().isEmpty() ? "" : " " + result.crashedDevices(),
                result.sinkErrors(),
                result.unexpectedErrors(),
                delta.collections(),
                delta.collectionTimeMillis(),
                formatBytes(delta.heapUsedBytes()));

        System.out.println("These figures are a demonstration. Only runs recorded under "
                + "experiments/results/ count as results.");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "undefined";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private Main() {
    }
}
