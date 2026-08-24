package io.fleet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final long T0 = 1_787_600_000_000L;
    private static final long RETRY_MILLIS = 10_000L;

    /** A clock the test moves, so a retry interval costs no wall-clock time. */
    private static final class MovableClock extends Clock {
        private long millis = T0;

        void advance(long by) {
            millis += by;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final List<String> closed = new ArrayList<>();

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
    @DisplayName("a non-positive retry interval is refused")
    void refusesAnImpossibleInterval() {
        // Zero would mean an attempt per call, which is the storm the interval
        // exists to prevent.
        assertThrows(IllegalArgumentException.class,
                () -> new LazyResource<>("the thing", () -> "x", closed::add, 0L, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new LazyResource<>("the thing", () -> "x", closed::add, -1L, clock));
    }
}
