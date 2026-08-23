package io.fleet.gateway;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.common.StoreException;
import io.fleet.common.TelemetryStore;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Declares devices failed when they stop proving they are alive.
 *
 * <p>The second of two detection paths, and the one that catches what the
 * first cannot. Phase 2's Last Will fires when a connection drops; a device
 * that stays connected but wedges — heartbeat path starved, loop blocked —
 * never produces one. Only a timeout notices that.
 *
 * <p>Sweeps on a timer rather than reacting to arrivals, because the event
 * being detected is the <em>absence</em> of a message. Nothing arrives to
 * trigger the check.
 */
public final class HealthMonitor implements AutoCloseable {

    private final DeviceRegistry registry;
    private final HealthPolicy policy;
    private final GatewayMetrics metrics;
    private final EventPublisher events;
    private final TelemetryStore store;
    private final long sweepIntervalMillis;
    private final ScheduledExecutorService scheduler;

    public HealthMonitor(
            DeviceRegistry registry,
            HealthPolicy policy,
            GatewayMetrics metrics,
            EventPublisher events,
            long sweepIntervalMillis) {
        this(registry, policy, metrics, events,
                new io.fleet.gateway.store.NoOpTelemetryStore(), sweepIntervalMillis);
    }

    public HealthMonitor(
            DeviceRegistry registry,
            HealthPolicy policy,
            GatewayMetrics metrics,
            EventPublisher events,
            TelemetryStore store,
            long sweepIntervalMillis) {

        this.store = store;
        this.registry = registry;
        this.policy = policy;
        this.metrics = metrics;
        this.events = events;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-health-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
                this::sweepQuietly, sweepIntervalMillis, sweepIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Runs one sweep, absorbing anything it throws.
     *
     * <p>A task that throws out of {@code scheduleAtFixedRate} is cancelled
     * silently by the executor. Detection would simply stop, and the gateway
     * would go on reporting every device healthy — the most dangerous possible
     * failure for this component, since silence is indistinguishable from a
     * fleet in good order.
     */
    private void sweepQuietly() {
        try {
            sweep(System.currentTimeMillis());
        } catch (RuntimeException e) {
            metrics.monitorError();
            System.err.println("health sweep failed: " + e);
        }
    }

    /**
     * Evaluates every device and announces what changed.
     *
     * <p>Takes the time as an argument so a test can drive the state machine
     * across thresholds without waiting for them.
     *
     * <p>Synchronised so a test-driven sweep cannot run alongside the timer's:
     * two sweeps observing the same threshold crossing would each announce it,
     * publishing duplicate failure events and double-counting a single failure
     * in the figures Pillar B is built from.
     *
     * @return the transitions announced
     */
    public synchronized List<HealthTransition> sweep(long nowMillis) {
        List<HealthTransition> transitions = registry.evaluateSilence(policy, nowMillis);
        for (HealthTransition transition : transitions) {
            announce(transition);
        }
        return transitions;
    }

    /** Applies a transition caused by an arriving heartbeat. */
    public void announce(HealthTransition transition) {
        if (transition.isFailure()) {
            metrics.failureDetected();
            System.err.printf("device %s declared OFFLINE after %d missed heartbeats%n",
                    transition.deviceId(), transition.missedHeartbeats());
        } else if (transition.isRecovery()) {
            metrics.recoveryObserved(transition.recoveryDurationMillis());
            System.out.printf("device %s recovered after %d ms%n",
                    transition.deviceId(), transition.recoveryDurationMillis());
        } else if (transition.to() == DeviceHealth.RECOVERING) {
            System.out.printf("device %s is heartbeating again; on probation%n",
                    transition.deviceId());
        }
        persist(transition);
        events.publish(transition);
    }

    /**
     * Writes the transition to history before announcing it.
     *
     * <p>Order matters: a consumer that reacts to the event and then queries
     * the store must not find the failure missing from the record it is
     * reacting to.
     */
    private void persist(HealthTransition transition) {
        var announceable = transition.eventType();
        if (announceable.isEmpty()) {
            return;
        }
        DeviceEventType type = announceable.get();
        try {
            store.recordEvent(new DeviceEventRecord(
                    transition.deviceId(), type, transition.from(), transition.to(),
                    transition.atMillis(), transition.missedHeartbeats(),
                    transition.recoveryDurationMillis()));
        } catch (StoreException e) {
            metrics.storeError();
            System.err.println("could not persist " + type + " for "
                    + transition.deviceId() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
