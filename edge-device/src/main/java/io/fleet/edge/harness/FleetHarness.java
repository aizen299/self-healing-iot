package io.fleet.edge.harness;

import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySinkFactory;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.DeviceCrashedException;
import io.fleet.edge.EdgeDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs a fleet of devices inside one JVM.
 *
 * <p>The default vehicle for Pillars A and C under ADR-003: fifty devices as
 * objects on a shared scheduler, rather than fifty processes, which would not
 * fit in this host's memory.
 *
 * <p>Thread count comes from the variant, because thread discipline is part
 * of what is being compared — the constrained fleet shares a single scheduler
 * thread, the naive fleet takes one per device.
 */
public final class FleetHarness implements AutoCloseable {

    private final DeviceConfig config;
    private final TelemetrySinkFactory sinks;
    private final List<EdgeDevice> devices;
    private final ScheduledExecutorService scheduler;

    private final Set<String> crashedDevices = ConcurrentHashMap.newKeySet();
    private final LongAdder sinkErrors = new LongAdder();
    private final LongAdder unexpectedErrors = new LongAdder();

    private volatile boolean started;
    private volatile long startedAtMillis;

    public FleetHarness(DeviceConfig config, TelemetrySinkFactory sinks) throws SinkException {
        this.config = config;
        this.sinks = sinks;
        this.devices = DeviceFactory.createFleet(config, sinks);
        int threads = config.variant().threadCount(config.deviceCount());
        this.scheduler = Executors.newScheduledThreadPool(threads, namedThreads());
        // Seeded at construction so result() before start() reports a
        // near-zero duration instead of the whole Unix epoch, which would look
        // like a completed run at approximately zero throughput.
        this.startedAtMillis = System.currentTimeMillis();
    }

    /**
     * Schedules every device.
     *
     * <p>Device starts are staggered across one publish interval. Releasing
     * fifty devices on the same millisecond would produce a periodic spike
     * rather than a steady rate, and the spike — not the implementation —
     * would dominate any latency measurement.
     */
    public void start() {
        if (started) {
            // Starting twice would put every device on two independent fixed-rate
            // timers, doubling the real publish rate while the run still reports
            // the configured one — a silently inflated throughput figure.
            throw new IllegalStateException("harness already started");
        }
        started = true;
        startedAtMillis = System.currentTimeMillis();
        long interval = config.publishIntervalMillis();
        long stagger = Math.max(1L, interval / Math.max(1, devices.size()));

        for (int i = 0; i < devices.size(); i++) {
            DeviceTask task = new DeviceTask(devices.get(i));
            task.future = scheduler.scheduleAtFixedRate(
                    task, i * stagger, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** Blocks for the configured run duration. */
    public void awaitRunDuration() throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(config.runDurationSeconds()));
    }

    /** Stops scheduling and collects the run's counters. */
    public FleetRunResult stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return result();
    }

    /** Snapshot of counters without stopping. */
    public FleetRunResult result() {
        long readings = 0L;
        long heartbeats = 0L;
        for (EdgeDevice device : devices) {
            readings += device.readingsPublished();
            heartbeats += device.heartbeatsPublished();
        }
        // Sorted because the backing set's iteration order varies between
        // otherwise identical runs, and a result field that does not reproduce
        // undermines the point of the reproducibility contract.
        List<String> crashed = new ArrayList<>(crashedDevices);
        Collections.sort(crashed);

        return new FleetRunResult(
                devices.size(),
                readings,
                heartbeats,
                crashed,
                sinkErrors.sum(),
                unexpectedErrors.sum(),
                System.currentTimeMillis() - startedAtMillis);
    }

    public List<EdgeDevice> devices() {
        return List.copyOf(devices);
    }

    @Override
    public void close() {
        if (!scheduler.isShutdown()) {
            stop();
        }
    }

    private ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "fleet-device-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * One device's scheduled tick.
     *
     * <p>Every exception is caught and accounted for. A task that throws out
     * of {@code scheduleAtFixedRate} is cancelled silently by the executor,
     * so an uncaught error would stop a device publishing while the run
     * carried on reporting success — exactly the kind of silent failure this
     * project forbids.
     */
    private final class DeviceTask implements Runnable {

        private final EdgeDevice device;
        private volatile ScheduledFuture<?> future;

        private DeviceTask(EdgeDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            try {
                long now = System.currentTimeMillis();
                // Heartbeat first, and independently: liveness should not
                // depend on a reading succeeding, and a transport hiccup on one
                // must not cost the other — they are separate messages on
                // separate topics.
                heartbeatIndependently(now);
                device.publishReading(now);
            } catch (DeviceCrashedException e) {
                crashedDevices.add(device.deviceId());
                cancelSelf();
                abandonSink();
            } catch (SinkException e) {
                sinkErrors.increment();
                System.err.println("sink rejected payload from " + device.deviceId()
                        + ": " + e.getMessage());
            } catch (RuntimeException e) {
                unexpectedErrors.increment();
                System.err.println("unexpected error in " + device.deviceId() + ": " + e);
            }
        }

        /**
         * Publishes the heartbeat, absorbing only transport failures.
         *
         * <p>A {@link DeviceCrashedException} deliberately propagates: the
         * device is dead, and the reading must not be attempted either.
         */
        private void heartbeatIndependently(long nowMillis) {
            try {
                device.publishHeartbeat(nowMillis);
            } catch (SinkException e) {
                sinkErrors.increment();
                System.err.println("sink rejected heartbeat from " + device.deviceId()
                        + ": " + e.getMessage());
            }
        }

        private void cancelSelf() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }

        /**
         * Releases the dead device's transport ungracefully.
         *
         * <p>Without this a crashed device keeps its broker connection open
         * for the rest of the run, so the broker still reports it ONLINE and
         * eventually sees a clean disconnect at shutdown — meaning the most
         * basic failure mode would produce no failure signal at all for
         * Phase 4 to detect.
         */
        private void abandonSink() {
            try {
                sinks.abandon(device.deviceId());
            } catch (SinkException e) {
                sinkErrors.increment();
                System.err.println("could not release the connection of crashed device "
                        + device.deviceId() + ": " + e.getMessage());
            }
        }
    }
}
