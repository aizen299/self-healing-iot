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
    private final LongAdder invalidPresence = new LongAdder();
    private final LongAdder handlerErrors = new LongAdder();
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
