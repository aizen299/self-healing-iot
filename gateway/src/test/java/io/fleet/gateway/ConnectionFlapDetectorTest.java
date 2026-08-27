package io.fleet.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFlapDetectorTest {

    /** A clock the test moves by hand, so nothing here waits on real time. */
    private static final class MovableClock extends Clock {
        // Starts at a large value rather than 0: the detector compares
        // timestamps by subtraction, and a window that only works because the
        // epoch happens to be zero is not a window.
        private long millis = 1_787_000_000_000L;

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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
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
        clock.advance(1_000L);
        detector.recordLoss("gw-1");
        clock.advance(1_000L);

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
            clock.advance(ConnectionFlapDetector.WINDOW_MILLIS + 1L);
        }
    }

    @Test
    @DisplayName("a new burst after a quiet spell is reported again")
    void aLaterBurstIsReportedAgain() {
        for (int i = 0; i < ConnectionFlapDetector.THRESHOLD; i++) {
            detector.recordLoss("gw-1");
        }
        clock.advance(ConnectionFlapDetector.WINDOW_MILLIS * 2);

        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertTrue(detector.recordLoss("gw-1").isPresent(),
                "the second outbreak matters as much as the first");
    }

    @Test
    @DisplayName("the window is measured, not assumed")
    void theWindowBoundaryHolds() {
        detector.recordLoss("gw-1");
        detector.recordLoss("gw-1");
        clock.advance(ConnectionFlapDetector.WINDOW_MILLIS + 1L);

        // The window has expired, so this is the first loss of a new one.
        assertTrue(detector.recordLoss("gw-1").isEmpty());
        assertEquals(60_000L, ConnectionFlapDetector.WINDOW_MILLIS);
        assertFalse(ConnectionFlapDetector.THRESHOLD < 2);
    }
}
