package io.fleet.edge.harness;

import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.DeviceCrashedException;
import io.fleet.edge.EdgeDevice;

import java.util.ArrayList;
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
import java.util.function.LongSupplier;

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
    private final List<EdgeDevice> devices;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;

    private final Set<String> crashedDevices = ConcurrentHashMap.newKeySet();
    private final LongAdder sinkErrors = new LongAdder();
    private final LongAdder unexpectedErrors = new LongAdder();

    private long startedAtMillis;

    public FleetHarness(DeviceConfig config, TelemetrySink sink) {
        this(config, sink, System::currentTimeMillis);
    }

    /** @param clock injectable so tests need not depend on wall-clock time */
    public FleetHarness(DeviceConfig config, TelemetrySink sink, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.devices = DeviceFactory.createFleet(config, sink);
        int threads = config.variant().threadCount(config.deviceCount());
        this.scheduler = Executors.newScheduledThreadPool(threads, namedThreads());
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
        startedAtMillis = clock.getAsLong();
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
        for (EdgeDevice device : devices) {
            readings += device.readingsPublished();
        }
        return new FleetRunResult(
                devices.size(),
                readings,
                new ArrayList<>(crashedDevices),
                sinkErrors.sum(),
                unexpectedErrors.sum(),
                clock.getAsLong() - startedAtMillis);
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
                device.publishReading(clock.getAsLong());
            } catch (DeviceCrashedException e) {
                crashedDevices.add(device.deviceId());
                cancelSelf();
            } catch (SinkException e) {
                sinkErrors.increment();
                System.err.println("sink rejected payload from " + device.deviceId()
                        + ": " + e.getMessage());
            } catch (RuntimeException e) {
                unexpectedErrors.increment();
                System.err.println("unexpected error in " + device.deviceId() + ": " + e);
            }
        }

        private void cancelSelf() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
