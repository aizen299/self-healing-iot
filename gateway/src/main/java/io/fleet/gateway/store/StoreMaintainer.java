package io.fleet.gateway.store;

import io.fleet.common.StoreException;
import io.fleet.common.TelemetryStore;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the store flushed and, if configured, pruned.
 *
 * <p>The flush timer is what bounds how long a reading can sit in the write
 * buffer. Without it a fleet that goes quiet — which is exactly what happens
 * when devices fail, the case this system exists to study — would leave its
 * last readings unwritten indefinitely, because the batch would never fill.
 */
public final class StoreMaintainer implements AutoCloseable {

    private final TelemetryStore store;
    private final StoreConfig config;
    private final ScheduledExecutorService scheduler;

    public StoreMaintainer(TelemetryStore store, StoreConfig config) {
        this.store = store;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::flushQuietly,
                config.flushIntervalMs(), config.flushIntervalMs(), TimeUnit.MILLISECONDS);

        if (config.pruningEnabled()) {
            scheduler.scheduleAtFixedRate(this::pruneQuietly,
                    config.pruneIntervalMins(), config.pruneIntervalMins(), TimeUnit.MINUTES);
        }
    }

    /**
     * A task that throws out of scheduleAtFixedRate is cancelled silently, so
     * one transient failure would stop all future flushes and the store would
     * quietly stop recording while the gateway looked healthy.
     */
    private void flushQuietly() {
        try {
            store.flush();
        } catch (StoreException e) {
            System.err.println("store flush failed: " + e.getMessage());
        }
    }

    private void pruneQuietly() {
        long cutoff = System.currentTimeMillis()
                - TimeUnit.HOURS.toMillis(config.retentionHours());
        try {
            int removed = store.pruneTelemetryBefore(cutoff);
            if (removed > 0) {
                System.out.println("pruned " + removed + " readings older than "
                        + config.retentionHours() + "h");
            }
        } catch (StoreException e) {
            System.err.println("store prune failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        // A final flush on the caller's thread: the scheduler is gone, and
        // whatever is still buffered would otherwise be lost at exit.
        flushQuietly();
    }
}
