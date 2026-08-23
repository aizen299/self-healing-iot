package io.fleet.edge.harness;

import java.util.List;

/**
 * Outcome of one harness run.
 *
 * <p>These are operational counters for demonstrating and debugging a run.
 * They become an experimental *result* only once a run is recorded under
 * {@code experiments/results/} with its full configuration, per the
 * reproducibility contract — printed console output is not a result.
 *
 * @param deviceCount      devices started
 * @param readingsPublished readings the devices report having published
 * @param heartbeatsPublished heartbeats the devices report having published
 * @param crashedDevices   ids of devices that died via injected CRASH
 * @param sinkErrors       publishes the sink rejected
 * @param unexpectedErrors errors that were neither a crash nor a sink failure
 * @param durationMillis   wall-clock duration of the run
 */
public record FleetRunResult(
        int deviceCount,
        long readingsPublished,
        long heartbeatsPublished,
        List<String> crashedDevices,
        long sinkErrors,
        long unexpectedErrors,
        long durationMillis) {

    /** Readings per second across the fleet. */
    public double throughputPerSecond() {
        return durationMillis <= 0 ? 0.0 : (readingsPublished * 1000.0) / durationMillis;
    }
}
