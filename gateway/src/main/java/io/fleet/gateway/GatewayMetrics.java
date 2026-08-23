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

    private final LongAdder telemetryAccepted = new LongAdder();
    private final LongAdder telemetryMalformed = new LongAdder();
    private final LongAdder telemetryInvalid = new LongAdder();
    private final LongAdder presenceEvents = new LongAdder();
    private final LongAdder unroutableMessages = new LongAdder();
    private final LongAdder connectionLosses = new LongAdder();

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

    public void connectionLost() {
        connectionLosses.increment();
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

    public long connectionLossCount() {
        return connectionLosses.sum();
    }

    public long rejectedCount() {
        return malformedCount() + invalidCount();
    }
}
