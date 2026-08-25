package io.fleet.common;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Holds a resource whose construction is allowed to fail, and retries rather
 * than giving up on it.
 *
 * <p>Written because this project had the same bug twice, in two modules. A
 * {@code KafkaProducer} resolves {@code bootstrap.servers} inside its
 * constructor and throws when nothing resolves, and both of the project's
 * producers were built exactly once, at process start:
 *
 * <ul>
 *   <li>the gateway caught the failure and substituted a no-op forwarder for
 *       the life of the process, so a broker that became resolvable seconds
 *       later was never noticed;</li>
 *   <li>the operator did not catch it at all, so a producer it could not
 *       build stopped it consuming failures — the announcement path taking
 *       down the recovery path with it.</li>
 * </ul>
 *
 * <p>Neither is an edge case under Kubernetes, where every pod in the
 * namespace starts at once and Kafka is much the slowest to become ready.
 * Phase 8 worked around the gateway's half in the deploy script, by waiting
 * for Kafka and then restarting the gateway: deploy-time choreography
 * compensating for a process that could not reconnect.
 *
 * <p>{@link #get()} returns {@code null} while the resource is unavailable
 * and callers must handle that. Returning null rather than throwing is the
 * whole point — both call sites are on paths that are required not to fail:
 * forwarding a downstream copy, and announcing a recovery that has already
 * happened.
 *
 * <p>Deliberately not a connection pool and not a health check. It knows only
 * whether construction succeeded. A resource that opened and later broke stays
 * held, because reconnecting an open client is the client library's job — the
 * Kafka producer does it already, and second-guessing it here would mean
 * deciding what "broken" means from the outside.
 *
 * <p>Not thread-safe by accident: {@code get} and {@code close} are called
 * from different threads at both call sites (a sender thread and a shutdown
 * hook; a poll thread and main), so both are synchronized.
 *
 * <p><b>The lock is held across {@code opener.get()}</b>, which for a Kafka
 * producer means it is held across a constructor that resolves DNS. That is
 * deliberate — releasing it would let two threads build two resources where
 * only one gets stored, and the loser would never be closed — but it has a
 * consequence worth stating: a {@code close()} racing an in-flight attempt
 * waits for that attempt to finish. Bounded by however long the opener takes,
 * which for an unresolvable address is immediate and for a slow resolver is
 * not.
 *
 * @param <T> the resource type; no {@code AutoCloseable} bound, because both
 *            call sites need a <em>bounded</em> close rather than the
 *            no-argument one, and pass it as {@code closer}
 */
public final class LazyResource<T> implements AutoCloseable {

    /**
     * How long to wait after a failed attempt, unless a caller says otherwise.
     *
     * <p>Lives here rather than in each call site so the gateway and the
     * operator cannot end up retrying the same broker at different rates for
     * no stated reason.
     */
    public static final long DEFAULT_RETRY_INTERVAL_MILLIS = 10_000L;

    private final String name;
    private final Supplier<T> opener;
    private final Consumer<T> closer;
    private final long retryIntervalNanos;
    private final LongSupplier nanoTime;

    private final LongAdder openFailures = new LongAdder();

    private T resource;
    /**
     * When the next attempt is allowed, on the monotonic timer.
     *
     * <p>{@code nanoTime} and not {@code Clock.millis()}, and the difference
     * is not academic: a wall clock steps. An NTP correction or a VM
     * resynchronising after the host sleeps can move it backwards by minutes,
     * and this class would then refuse to retry for the length of the step —
     * silently, because the attempt that would have logged never runs. The one
     * thing it exists to guarantee is that it keeps trying.
     *
     * <p>{@code unset} rather than 0, because nanoTime's origin is arbitrary
     * and may be negative, so 0 is not reliably "in the past".
     */
    private boolean attemptScheduled;
    private long nextAttemptNanos;
    private boolean closed;

    /** Retries every {@link #DEFAULT_RETRY_INTERVAL_MILLIS} on the system timer. */
    public LazyResource(String name, Supplier<T> opener, Consumer<T> closer) {
        this(name, opener, closer, DEFAULT_RETRY_INTERVAL_MILLIS, System::nanoTime);
    }

    /**
     * @param name                what this holds, for log lines
     * @param opener              builds the resource; may throw
     * @param closer              releases it, and should be bounded in time
     * @param retryIntervalMillis how long to wait after a failed attempt
     * @param nanoTime            monotonic nanosecond source, normally
     *                            {@code System::nanoTime}; a test supplies one
     *                            it can move
     */
    public LazyResource(String name, Supplier<T> opener, Consumer<T> closer,
            long retryIntervalMillis, LongSupplier nanoTime) {
        if (retryIntervalMillis <= 0L) {
            throw new IllegalArgumentException(
                    "retry interval must be positive, got " + retryIntervalMillis);
        }
        this.name = name;
        this.opener = opener;
        this.closer = closer;
        this.retryIntervalNanos = TimeUnit.MILLISECONDS.toNanos(retryIntervalMillis);
        this.nanoTime = nanoTime;
    }

    /**
     * The resource, or {@code null} while it cannot be built.
     *
     * <p>At most one construction attempt per retry interval, so a caller on a
     * hot path can ask every time without turning an outage into a storm of
     * connection attempts.
     *
     * <p><b>The attempt runs on the calling thread</b> and takes as long as
     * the opener does — for a Kafka producer, a constructor that resolves DNS.
     * Each call site has to decide whether that is acceptable on the thread it
     * calls from. The gateway answers by calling this only on its dedicated
     * sender thread; the operator calls it from the consumer poll loop, where
     * the budget is {@code max.poll.interval.ms} (300 s by default) and one
     * resolution fits comfortably inside it.
     */
    public synchronized T get() {
        if (resource != null || closed) {
            return resource;
        }
        long now = nanoTime.getAsLong();
        // Subtraction, not `now < nextAttemptNanos`: nanoTime wraps, and the
        // difference stays correct across the wrap where the comparison does
        // not.
        if (attemptScheduled && now - nextAttemptNanos < 0L) {
            return null;
        }
        try {
            resource = opener.get();
            if (openFailures.sum() > 0L) {
                System.out.println(name + " is available again, after "
                        + openFailures.sum() + " failed attempt(s)");
            }
            return resource;
        } catch (RuntimeException e) {
            openFailures.increment();
            attemptScheduled = true;
            nextAttemptNanos = now + retryIntervalNanos;
            // The cause, not just the message: KafkaProducer's constructor
            // reports "Failed to construct kafka producer" and puts the actual
            // reason — an unresolvable bootstrap address, a rejected setting —
            // underneath, so the message alone says nothing about what to fix.
            //
            // Logged on every attempt rather than only the first. Retrying
            // cannot tell "the broker is not up yet" from "this configuration
            // will never work", and a line every interval is what makes the
            // second case findable. The interval is what keeps it bounded.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("could not open " + name + ", retrying in "
                    + TimeUnit.NANOSECONDS.toMillis(retryIntervalNanos) + " ms: "
                    + e.getMessage() + " (" + cause + ")");
            return null;
        }
    }

    /** Whether the resource is currently held. */
    public synchronized boolean isOpen() {
        return resource != null;
    }

    /** Construction attempts that failed; each one is an interval of downtime. */
    public long openFailureCount() {
        return openFailures.sum();
    }

    /**
     * Releases the resource if it was ever built, and stops retrying.
     *
     * <p>Idempotent, and never throws: this runs on shutdown paths where the
     * things after it — flushing a store, disconnecting from MQTT — matter
     * more than a client that failed to close.
     */
    @Override
    public synchronized void close() {
        closed = true;
        T open = resource;
        resource = null;
        if (open == null) {
            return;
        }
        try {
            closer.accept(open);
        } catch (RuntimeException e) {
            System.err.println(name + " failed to close cleanly: " + e.getMessage());
        }
    }
}
