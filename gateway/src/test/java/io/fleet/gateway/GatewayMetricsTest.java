package io.fleet.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The counters, and in particular the two numbers that are not the same.
 *
 * <p>{@code recoveriesObserved} counts recoveries; {@code recoveryDurationSamples}
 * counts the ones that measured something. Phase 10 found them conflated —
 * the mean divided a total of durations by a count that included recoveries
 * contributing none — and nothing had ever tested this class, so nothing
 * failed.
 */
class GatewayMetricsTest {

    private final GatewayMetrics metrics = new GatewayMetrics();

    @Test
    @DisplayName("the mean is -1 until something has actually recovered")
    void meanIsUndefinedBeforeAnyRecovery() {
        // Not 0. A fleet that has never recovered anything and a fleet that
        // recovers instantly are different situations, and /health reports
        // this number directly.
        assertEquals(-1L, metrics.meanRecoveryMillis());
    }

    @Test
    @DisplayName("the mean divides by the recoveries that were timed, not by all of them")
    void doesNotDivideByUntimedRecoveries() {
        // The bug this test exists for: dividing 3000 by 3 instead of by 2
        // reports 1000 ms for two recoveries that took 1500 ms each, and the
        // error grows with every clock disagreement.
        metrics.recoveryObserved(1_000L);
        metrics.recoveryObserved(2_000L);
        metrics.recoveryObserved(-5L);

        assertEquals(3L, metrics.recoveriesObservedCount(), "all three happened");
        assertEquals(2L, metrics.recoveryDurationSampleCount(), "two were measurable");
        assertEquals(1_500L, metrics.meanRecoveryMillis());
    }

    @Test
    @DisplayName("a zero duration is not a recovery that took no time")
    void treatsZeroAsUnmeasurable() {
        metrics.recoveryObserved(0L);

        assertEquals(1L, metrics.recoveriesObservedCount());
        assertEquals(0L, metrics.recoveryDurationSampleCount());
        assertEquals(-1L, metrics.meanRecoveryMillis(), "nothing measurable has happened yet");
        assertEquals(0L, metrics.recoveryDurationTotalMillis());
    }

    @Test
    @DisplayName("a duration lands in the first bucket whose bound it does not exceed")
    void bucketsOnTheUpperBound() {
        // le is inclusive, so exactly 500 belongs in the 500 bucket and 501
        // does not. Off by one here silently shifts every quantile the
        // dashboard draws.
        metrics.recoveryObserved(500L);
        metrics.recoveryObserved(501L);
        metrics.recoveryObserved(60_000L);
        metrics.recoveryObserved(60_001L);

        assertArrayEquals(new long[] {500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L},
                metrics.recoveryBucketBoundsMillis());
        // 500 | 1000 | 2000 | 5000 | 10000 | 30000 | 60000 | overflow
        assertArrayEquals(new long[] {1L, 1L, 0L, 0L, 0L, 0L, 1L, 1L},
                metrics.recoveryBucketCounts());
    }

    @Test
    @DisplayName("the bucket bounds cannot be edited through the accessor")
    void handsOutACopyOfTheBounds() {
        // Shared static state: a caller mutating what it was handed would move
        // the histogram's bounds for every instance in the JVM.
        long[] bounds = metrics.recoveryBucketBoundsMillis();
        bounds[0] = 999L;

        assertEquals(500L, metrics.recoveryBucketBoundsMillis()[0]);
    }

    @Test
    @DisplayName("bucket counts sum to the sample count, which is what the histogram needs")
    void bucketsAccountForEverySample() {
        metrics.recoveryObserved(120L);
        metrics.recoveryObserved(4_000L);
        metrics.recoveryObserved(90_000L);
        metrics.recoveryObserved(-1L);

        long inBuckets = 0L;
        for (long count : metrics.recoveryBucketCounts()) {
            inBuckets += count;
        }

        assertEquals(metrics.recoveryDurationSampleCount(), inBuckets,
                "a sample outside every bucket would make the +Inf total disagree with _count");
    }
}
