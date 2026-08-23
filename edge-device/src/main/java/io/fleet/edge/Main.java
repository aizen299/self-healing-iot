package io.fleet.edge;

import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySinkFactory;
import io.fleet.edge.harness.FleetHarness;
import io.fleet.edge.harness.FleetRunResult;
import io.fleet.edge.metrics.GcSnapshot;
import io.fleet.edge.mqtt.MqttConfig;
import io.fleet.edge.mqtt.MqttSinkFactory;
import io.fleet.edge.sink.CountingSinkFactory;

import java.util.Locale;

/**
 * Runs a simulated fleet for a fixed duration and prints a summary.
 *
 * <p>Telemetry goes wherever {@code FLEET_SINK} says. The default counting
 * sink needs no broker and keeps transport cost out of the numbers; the MQTT
 * sink publishes to a real broker with one connection per device.
 *
 * <p>The printed figures are a demonstration, not a result. Nothing here may
 * be quoted in documentation or the report unless it came from a run recorded
 * under {@code experiments/results/} with its full configuration attached.
 */
public final class Main {

    public static void main(String[] args) throws InterruptedException, SinkException {
        DeviceConfig config = DeviceConfig.fromEnv();
        MqttConfig mqttConfig = config.sink() == SinkType.MQTT ? MqttConfig.fromEnv() : null;

        printHeader(config, mqttConfig);

        // Declared in this order so the harness closes first and the sinks
        // second: devices must stop publishing before their connections are
        // released, and each MQTT connection needs a proper DISCONNECT so the
        // broker does not fire the device's Last Will on an orderly shutdown.
        try (TelemetrySinkFactory sinks = createSinkFactory(config, mqttConfig);
             FleetHarness harness = new FleetHarness(config, sinks)) {

            Runtime.getRuntime().addShutdownHook(new Thread(harness::close, "fleet-shutdown"));

            GcSnapshot before = GcSnapshot.capture();
            harness.start();
            harness.awaitRunDuration();
            FleetRunResult result = harness.stop();
            GcSnapshot delta = GcSnapshot.capture().since(before);

            printSummary(result, sinks, delta);
        }
    }

    private static TelemetrySinkFactory createSinkFactory(DeviceConfig config, MqttConfig mqtt) {
        return switch (config.sink()) {
            case COUNTING -> new CountingSinkFactory();
            case MQTT -> new MqttSinkFactory(
                    mqtt,
                    config.failureMode() == FailureMode.NETWORK_INTERRUPTION
                            ? config.failAfterReadings() : 0L,
                    config.interruptDurationMillis());
        };
    }

    private static void printHeader(DeviceConfig config, MqttConfig mqtt) {
        System.out.printf(Locale.ROOT, """
                === edge-device simulator (Phase 2) ===
                variant           : %s
                sink              : %s%s
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
                config.sink(),
                mqtt == null ? "" : " (" + mqtt.brokerUrl() + ", QoS " + mqtt.qos() + ")",
                config.deviceCount(),
                config.publishIntervalMillis(),
                config.runDurationSeconds(),
                config.failureMode(),
                describeFailureTiming(config),
                config.seed(),
                System.getProperty("java.vm.name"),
                System.getProperty("java.version"),
                formatBytes(Runtime.getRuntime().maxMemory()),
                GcSnapshot.collectorNames());
    }

    private static String describeFailureTiming(DeviceConfig config) {
        if (config.failureMode() == FailureMode.NONE) {
            return "";
        }
        String timing = " (after " + config.failAfterReadings() + " readings";
        if (config.failureMode() == FailureMode.NETWORK_INTERRUPTION) {
            timing += ", for " + config.interruptDurationMillis() + " ms";
        }
        return timing + ")";
    }

    private static void printSummary(
            FleetRunResult result, TelemetrySinkFactory sinks, GcSnapshot delta) {
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
                sinks.payloadCount(),
                formatBytes(sinks.byteCount()),
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
