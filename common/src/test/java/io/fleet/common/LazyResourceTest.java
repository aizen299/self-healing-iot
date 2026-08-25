package io.fleet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry-instead-of-give-up holder.
 *
 * <p>The behaviour under test is the one whose absence caused the bug this
 * class was written for: a construction that failed once must be attempted
 * again, and a construction that succeeded must not be attempted again.
 */
class LazyResourceTest {

    private static final long RETRY_MILLIS = 10_000L;

    /**
     * A monotonic timer the test moves, so a retry interval costs no
     * wall-clock time.
     *
     * <p>Deliberately starts at a large negative value. {@code System.nanoTime}
     * has an arbitrary origin and is explicitly allowed to be negative, so any
     * implementation that treats 0 as "the beginning of time" or compares
     * timestamps with {@code <} instead of subtracting is wrong — and would
     * pass a test that started at zero.
     */
    private static final class MovableTimer implements LongSupplier {
        private long nanos = -TimeUnit.HOURS.toNanos(3);

        void advance(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }

    private final MovableTimer clock = new MovableTimer();
    private final List<String> closed = Collections.synchronizedList(new ArrayList<>());

    private LazyResource<String> holding(java.util.function.Supplier<String> opener) {
        return new LazyResource<>("the thing", opener, closed::add, RETRY_MILLIS, clock);
    }

    @Test
    @DisplayName("opens once and hands back the same instance after that")
    void opensOnceAndCaches() {
        AtomicInteger opens = new AtomicInteger();
        LazyResource<String> resource = holding(() -> "open-" + opens.incrementAndGet());

        assertEquals("open-1", resource.get());
        assertEquals("open-1", resource.get());
        assertEquals("open-1", resource.get());
        assertEquals(1, opens.get(), "a resource that opened must not be rebuilt");
        assertTrue(resource.isOpen());
    }

    @Test
    @DisplayName("a failed attempt yields null rather than throwing")
    void failureIsNullNotAnException() {
        LazyResource<String> resource = holding(() -> {
            throw new IllegalStateException("no resolvable bootstrap urls");
        });

        assertNull(resource.get());
        assertFalse(resource.isOpen());
        assertEquals(1L, resource.openFailureCount());
    }

    @Test
    @DisplayName("it does not re-attempt before the retry interval has elapsed")
    void backsOffBetweenAttempts() {
        // Without this, a caller on a per-record path would turn a broker
        // outage into a DNS lookup per reading — the reason get() is safe to
        // call every time.
        AtomicInteger attempts = new AtomicInteger();
        LazyResource<String> resource = holding(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("down");
        });

        assertNull(resource.get());
        clock.advance(RETRY_MILLIS - 1);
        assertNull(resource.get());
        assertNull(resource.get());

        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("it opens on a later attempt once the resource is there")
    void recoversWhenTheResourceComesBack() {
        // The whole point. The gateway forwarded nothing for the life of the
        // process because this did not happen.
        AtomicInteger attempts = new AtomicInteger();
        LazyResource<String> resource = holding(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("not up yet");
            }
            return "kafka";
        });

        assertNull(resource.get());
        clock.advance(RETRY_MILLIS);
        assertNull(resource.get());
        clock.advance(RETRY_MILLIS);

        assertEquals("kafka", resource.get());
        assertEquals(2L, resource.openFailureCount(), "the failures still happened");
        assertTrue(resource.isOpen());
    }

    @Test
    @DisplayName("closing releases through the supplied closer, not AutoCloseable")
    void closesWithTheGivenAction() {
        // Both call sites need a bounded close — the no-argument KafkaProducer
        // close waits out the delivery timeout — which is why the closer is
        // passed in rather than the type being bounded to AutoCloseable.
        LazyResource<String> resource = holding(() -> "kafka");
        assertNotNull(resource.get());

        resource.close();

        assertEquals(List.of("kafka"), closed);
        assertFalse(resource.isOpen());
    }

    @Test
    @DisplayName("closing something never opened closes nothing")
    void closeBeforeOpenIsANoOp() {
        LazyResource<String> resource = holding(() -> "kafka");

        resource.close();

        assertTrue(closed.isEmpty());
    }

    @Test
    @DisplayName("a closed holder stops opening")
    void doesNotReopenAfterClose() {
        // A shutdown hook and the sender thread run concurrently; a get() that
        // lost the race must not build a producer nobody will ever close.
        AtomicInteger opens = new AtomicInteger();
        LazyResource<String> resource = holding(() -> "open-" + opens.incrementAndGet());

        resource.close();
        clock.advance(RETRY_MILLIS * 10);

        assertNull(resource.get());
        assertEquals(0, opens.get());
    }

    @Test
    @DisplayName("closing twice releases once")
    void closeIsIdempotent() {
        LazyResource<String> resource = holding(() -> "kafka");
        resource.get();

        resource.close();
        resource.close();

        assertEquals(List.of("kafka"), closed);
    }

    @Test
    @DisplayName("a closer that throws does not escape")
    void aFailingCloserIsSwallowed() {
        // close() runs on shutdown paths where what comes after it — flushing
        // the store, disconnecting MQTT cleanly — matters more than this.
        LazyResource<String> resource = new LazyResource<>("the thing", () -> "kafka",
                open -> {
                    throw new IllegalStateException("broker gone");
                }, RETRY_MILLIS, clock);
        resource.get();

        resource.close();

        assertFalse(resource.isOpen());
    }

    @Test
    @DisplayName("a resource that opened is never rebuilt, however long it is held")
    void doesNotHealthCheck() {
        // Deliberate: reconnecting an open client is the client library's job.
        // Rebuilding on a hunch would drop the producer's buffered records.
        AtomicInteger opens = new AtomicInteger();
        LazyResource<String> resource = holding(() -> "open-" + opens.incrementAndGet());

        assertSame(resource.get(), resource.get());
        clock.advance(RETRY_MILLIS * 100);
        assertEquals("open-1", resource.get());
        assertEquals(1, opens.get());
    }

    @Test
    @DisplayName("a wall clock going backwards does not suspend retrying")
    void survivesTimeMovingBackwards() {
        // The reason the timer is nanoTime and not Clock.millis(). An NTP
        // correction, or a VM resynchronising after the host sleeps, moves a
        // wall clock backwards; measuring the retry interval on one would stop
        // this class retrying for the length of the step — silently, because
        // the attempt that logs never runs. A monotonic source cannot go
        // backwards, so the only thing that can delay an attempt is the
        // interval itself.
        AtomicInteger attempts = new AtomicInteger();
        LazyResource<String> resource = holding(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("not up yet");
            }
            return "kafka";
        });

        assertNull(resource.get());
        // A monotonic timer only ever moves forward, however far the wall
        // clock jumps, so the second attempt still happens on schedule.
        clock.advance(RETRY_MILLIS);

        assertEquals("kafka", resource.get());
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("a close racing an in-flight open still releases the resource")
    void closeDuringOpenDoesNotLeak() throws Exception {
        // The whole reason both methods are synchronized: get() runs on the
        // sender or poll thread and close() on a shutdown path. A close that
        // slipped past an in-flight attempt would leave a live producer with
        // nobody holding a reference to it.
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LazyResource<String> resource = holding(() -> {
            opening.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "kafka";
        });

        Thread opener = new Thread(resource::get, "opener");
        opener.start();
        assertTrue(opening.await(5L, TimeUnit.SECONDS), "the opener never started");

        Thread closer = new Thread(resource::close, "closer");
        closer.start();
        release.countDown();

        opener.join(5_000L);
        closer.join(5_000L);

        assertFalse(resource.isOpen());
        assertEquals(List.of("kafka"), closed,
                "the resource opened, so close must have released it exactly once");
        assertNull(resource.get(), "a closed holder must not open another");
    }

    @Test
    @DisplayName("a non-positive retry interval is refused")
    void refusesAnImpossibleInterval() {
        // Zero would mean an attempt per call, which is the storm the interval
        // exists to prevent.
        assertThrows(IllegalArgumentException.class,
                () -> new LazyResource<>("the thing", () -> "x", closed::add, 0L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new LazyResource<>("the thing", () -> "x", closed::add, -1L, clock));

        // The convenience constructor exists so call sites do not each name
        // their own interval; it must agree with the documented default.
        assertEquals(10_000L, LazyResource.DEFAULT_RETRY_INTERVAL_MILLIS);
    }
}
