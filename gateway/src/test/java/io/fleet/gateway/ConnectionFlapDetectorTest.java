package io.fleet.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFlapDetectorTest {

    /**
     * A monotonic timer the test moves by hand.
     *
     * <p>Starts negative on purpose. {@code System.nanoTime}'s origin is
     * arbitrary and routinely negative on a freshly booted machine, so a
     * detector that only works because its first reading happened to be
     * positive is not one that works.
     */
    private static final class MovableTimer implements LongSupplier {
        private long nanos = -TimeUnit.HOURS.toNanos(3);

        void advanceMillis(long by) {
            nanos += TimeUnit.MILLISECONDS.toNanos(by);
        }

        @Override
        public long getAsLong() {
            return nanos;
        }
    }

    private final MovableTimer clock = new MovableTimer();
    private final ConnectionFlapDetector detector = new ConnectionFlapDetector(clock);

    @Test
    @DisplayName("a broker restart is one loss and says nothing")
    void oneLossIsNotAPattern() {
        assertTrue(detector.recordLoss("gw-1").isEmpty());
    }

    @Test
    @DisplayName("two losses are still bad luck")
    void twoLossesAreStillQuiet() {
        detector.recordLoss("gw-1");
        assertTrue(detector.recordLoss("gw-1").isEmpty());
    }

    @Test
    @DisplayName("three losses in a minute name the likely cause")
    void threeLossesInAWindowExplainThemselves() {
        detector.recordLoss("gw-1");
        clock.advanceMillis(1_000L);
        detector.recordLoss("gw-1");
        clock.advanceMillis(1_000L);

        Optional<String> warning = detector.recordLoss("gw-1");

        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("gw-1"),
                "the message must name the client id that is in conflict");
        assertTrue(warning.get().contains("singleton"),
                "and say what to do about it");
    }

    @Test
    @DisplayName("the explanation is given once, not on every subsequent loss")
    void theWarningIsNotRepeatedWithinTheWindow() {
        for (int i = 0; i < ConnectionFlapDetector.THRESHOLD; i++) {
            detector.recordLoss("gw-1");
        }
        assertTrue(detector.recordLoss("gw-1").isEmpty(),
                "a flapping connection must not also flood the log");
    }

    @Test
    @DisplayName("losses spread out over hours never trip it")
    void slowLossesDoNotAccumulate() {
        for (int i = 0; i < 10; i++) {
            assertTrue(detector.recordLoss("gw-1").isEmpty(),
                    "a loss an hour is not a flap");
            clock.advanceMillis(ConnectionFlapDetector.WINDOW_MILLIS + 1L);
        }
    }

    @Test
    @DisplayName("a new burst after a quiet spell is reported again")
    void aLaterBurstIsReportedAgain() {
        for (int i = 0; i < ConnectionFlapDetector.THRESHOLD; i++) {
            detector.recordLoss("gw-1");
        }
        clock.advanceMillis(ConnectionFlapDetector.WINDOW_MILLIS * 2);

        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertTrue(detector.recordLoss("gw-1").isPresent(),
                "the second outbreak matters as much as the first");
    }

    @Test
    @DisplayName("losses on either side of the boundary do not add up")
    void theWindowBoundaryHolds() {
        detector.recordLoss("gw-1");
        detector.recordLoss("gw-1");
        clock.advanceMillis(ConnectionFlapDetector.WINDOW_MILLIS + 1L);

        // Two before the boundary and one after is not three in a window, so
        // this must stay quiet — and it must take a full THRESHOLD more to
        // speak, which is what the next two lines prove.
        assertTrue(detector.recordLoss("gw-1").isEmpty(),
                "the two losses before the boundary belong to a window that has closed");
        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertTrue(detector.recordLoss("gw-1").isPresent(),
                "three inside the new window is a pattern again");
    }

    @Test
    @DisplayName("a loss just inside the boundary still counts")
    void aLossJustInsideTheWindowStillCounts() {
        detector.recordLoss("gw-1");
        detector.recordLoss("gw-1");
        clock.advanceMillis(ConnectionFlapDetector.WINDOW_MILLIS - 1L);

        assertTrue(detector.recordLoss("gw-1").isPresent(),
                "still inside the window, so this is the third and it should speak");
    }
}
