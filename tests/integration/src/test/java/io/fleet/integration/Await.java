package io.fleet.integration;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waits for something that is a broker round-trip away, and says what it was
 * waiting for when it gives up.
 *
 * <p>These suites drive a real broker, so nearly every assertion is preceded
 * by a wait, and the conditions worth waiting for are exactly the ones whose
 * failure is interesting: a device that never went offline, a fleet that
 * never all stopped. A bare "condition not met" throws that away — it reports
 * that the suite timed out and nothing about what did not happen. The
 * description and the state snapshot are what turn a timeout back into a
 * diagnosis, and both are cheap because the snapshot is only taken on the way
 * to failing.
 *
 * <p>One copy, not one per suite: this was duplicated in two test classes,
 * which meant improving the message in one left the other reporting nothing.
 */
final class Await {

    /** Generous enough for a loaded CI runner, short enough to fail a build. */
    private static final long TIMEOUT_MILLIS = 15_000L;
    private static final long POLL_MILLIS = 20L;

    private Await() {
    }

    static void until(String what, BooleanSupplier condition) {
        until(what, condition, () -> null);
    }

    /**
     * @param what           what is being waited for, phrased to follow
     *                       "timed out waiting for"
     * @param condition      polled until true or the deadline passes
     * @param stateOnTimeout what to report if it never became true, evaluated
     *                       only on the failure path
     */
    static void until(String what, BooleanSupplier condition,
                      Supplier<String> stateOnTimeout) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, e);
            }
        }
        String state = stateOnTimeout.get();
        throw new AssertionError("timed out after " + TIMEOUT_MILLIS
                + "ms waiting for " + what + (state == null ? "" : " — " + state));
    }
}
