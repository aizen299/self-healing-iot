package io.fleet.gateway;

import java.util.concurrent.atomic.LongAdder;

/**
 * Gateway-wide counters.
 *
 * <p>Rejections are split by cause. A payload the gateway cannot parse and a
 * reading whose values are impossible point at different faults — a producer
 * speaking the wrong format versus a sensor or simulation problem — and
 * collapsing them into one number would hide which is happening.
 */
public final class GatewayMetrics {

    /**
     * Upper bounds, in milliseconds, of the recovery-duration histogram.
     *
     * <p>Placed around what this fleet actually does: detection is a few
     * missed heartbeats and a replacement is a JVM start, so recoveries land
     * in the low seconds. Buckets far below that would all be empty and
     * buckets far above would put every recovery in one, which is the same as
     * not having a histogram. The implicit +Inf bucket above 60 s is where a
     * recovery that went wrong shows up.
     */
    private static final long[] RECOVERY_BUCKET_BOUNDS_MILLIS =
            {500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L};

    private final LongAdder telemetryAccepted = new LongAdder();
    private final LongAdder telemetryMalformed = new LongAdder();
    private final LongAdder telemetryInvalid = new LongAdder();
    private final LongAdder presenceEvents = new LongAdder();
    private final LongAdder unroutableMessages = new LongAdder();
    private final LongAdder invalidPresence = new LongAdder();
    private final LongAdder handlerErrors = new LongAdder();
    private final LongAdder connectionLosses = new LongAdder();
    private final LongAdder heartbeatsAccepted = new LongAdder();
    private final LongAdder heartbeatsMalformed = new LongAdder();
    private final LongAdder failuresDetected = new LongAdder();
    private final LongAdder recoveriesObserved = new LongAdder();
    private final LongAdder recoveryDurationTotalMillis = new LongAdder();
    private final LongAdder recoveryDurationSamples = new LongAdder();
    /** One more slot than there are bounds: the last is the +Inf overflow. */
    private final LongAdder[] recoveryBuckets = newBuckets();
    private final LongAdder monitorErrors = new LongAdder();
    private final LongAdder eventPublishFailures = new LongAdder();
    private final LongAdder storeErrors = new LongAdder();

    public void telemetryAccepted() {
        telemetryAccepted.increment();
    }

    public void telemetryMalformed() {
        telemetryMalformed.increment();
    }

    public void telemetryInvalid() {
        telemetryInvalid.increment();
    }

    public void presenceEvent() {
        presenceEvents.increment();
    }

    /** A message on a topic the gateway subscribed to but cannot interpret. */
    public void unroutableMessage() {
        unroutableMessages.increment();
    }

    /**
     * A status message whose payload is not a presence value. Counted apart
     * from an unroutable topic: that means another publisher is using the
     * fleet topic space, while this means one of our own devices is speaking a
     * protocol the gateway does not recognise.
     */
    public void invalidPresence() {
        invalidPresence.increment();
    }

    /**
     * An error escaping a message handler. Should stay zero; a non-zero value
     * means messages of some shape are being dropped.
     */
    public void handlerError() {
        handlerErrors.increment();
    }

    public void connectionLost() {
        connectionLosses.increment();
    }

    public void heartbeatAccepted() {
        heartbeatsAccepted.increment();
    }

    public void heartbeatMalformed() {
        heartbeatsMalformed.increment();
    }

    /** A device was declared failed. Pillar B counts these. */
    public void failureDetected() {
        failuresDetected.increment();
    }

    /**
     * A failed device was confirmed back in service.
     *
     * <p>The duration is detection-to-confirmation, not fault-to-recovery: the
     * gateway cannot know when the device actually broke. Anything reporting
     * MTTR from this must say which interval it is.
     */
    public void recoveryObserved(long durationMillis) {
        recoveriesObserved.increment();
        if (durationMillis <= 0L) {
            // Not a measurement. The two ends can come from clocks that
            // disagree, and a zero or negative interval would drag the mean
            // down while claiming to be a recovery that took no time.
            //
            // Counted separately from recoveriesObserved for exactly that
            // reason: dividing the duration total by the number of recoveries
            // would divide by a count that includes the ones excluded here.
            return;
        }
        recoveryDurationTotalMillis.add(durationMillis);
        recoveryDurationSamples.increment();
        recoveryBuckets[bucketFor(durationMillis)].increment();
    }

    private static LongAdder[] newBuckets() {
        LongAdder[] buckets = new LongAdder[RECOVERY_BUCKET_BOUNDS_MILLIS.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        return buckets;
    }

    private static int bucketFor(long durationMillis) {
        for (int i = 0; i < RECOVERY_BUCKET_BOUNDS_MILLIS.length; i++) {
            if (durationMillis <= RECOVERY_BUCKET_BOUNDS_MILLIS[i]) {
                return i;
            }
        }
        return RECOVERY_BUCKET_BOUNDS_MILLIS.length;
    }

    /** The histogram's {@code le} bounds, in milliseconds. */
    public long[] recoveryBucketBoundsMillis() {
        return RECOVERY_BUCKET_BOUNDS_MILLIS.clone();
    }

    /**
     * How many recoveries fell in each bucket, <em>not</em> cumulative, with
     * the overflow above the last bound in the final slot.
     *
     * <p>Prometheus wants cumulative counts; accumulating them is the
     * exporter's arithmetic. Keeping the raw counts here means this class does
     * not have to know what format it is being read into.
     */
    public long[] recoveryBucketCounts() {
        long[] counts = new long[recoveryBuckets.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = recoveryBuckets[i].sum();
        }
        return counts;
    }

    /** Total of the durations that were measurable. */
    public long recoveryDurationTotalMillis() {
        return recoveryDurationTotalMillis.sum();
    }

    /** How many recoveries contributed a duration, which is not all of them. */
    public long recoveryDurationSampleCount() {
        return recoveryDurationSamples.sum();
    }

    /** The health sweep threw. Should stay zero; non-zero means detection stalled. */
    public void monitorError() {
        monitorErrors.increment();
    }

    public void eventPublishFailure() {
        eventPublishFailures.increment();
    }

    /** A read or write the store rejected. History is lossy when this moves. */
    public void storeError() {
        storeErrors.increment();
    }

    public long storeErrorCount() {
        return storeErrors.sum();
    }

    public long heartbeatsAcceptedCount() {
        return heartbeatsAccepted.sum();
    }

    public long heartbeatsMalformedCount() {
        return heartbeatsMalformed.sum();
    }

    public long failuresDetectedCount() {
        return failuresDetected.sum();
    }

    public long recoveriesObservedCount() {
        return recoveriesObserved.sum();
    }

    public long monitorErrorCount() {
        return monitorErrors.sum();
    }

    public long eventPublishFailureCount() {
        return eventPublishFailures.sum();
    }

    /**
     * Mean observed recovery time, or -1 when nothing has recovered yet.
     *
     * <p>An operational figure for the health endpoint. It is not a result:
     * only a run recorded under experiments/ counts as one.
     */
    public long meanRecoveryMillis() {
        long count = recoveryDurationSamples.sum();
        return count == 0L ? -1L : recoveryDurationTotalMillis.sum() / count;
    }

    public long acceptedCount() {
        return telemetryAccepted.sum();
    }

    public long malformedCount() {
        return telemetryMalformed.sum();
    }

    public long invalidCount() {
        return telemetryInvalid.sum();
    }

    public long presenceCount() {
        return presenceEvents.sum();
    }

    public long unroutableCount() {
        return unroutableMessages.sum();
    }

    public long invalidPresenceCount() {
        return invalidPresence.sum();
    }

    public long handlerErrorCount() {
        return handlerErrors.sum();
    }

    public long connectionLossCount() {
        return connectionLosses.sum();
    }

    public long rejectedCount() {
        return malformedCount() + invalidCount();
    }
}
