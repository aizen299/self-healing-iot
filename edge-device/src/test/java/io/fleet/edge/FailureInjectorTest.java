package io.fleet.edge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failures trigger on a reading count rather than elapsed time, so an
 * experiment injects the same failure at the same point regardless of how
 * fast the host runs.
 */
class FailureInjectorTest {

    @Test
    void noneNeverFires() {
        FailureInjector injector = new FailureInjector(FailureMode.NONE, 0L, 10);

        assertFalse(injector.shouldCrash(0L));
        assertFalse(injector.shouldCrash(1_000_000L));
        assertEquals(1, injector.publishesForTick(1_000_000L));
    }

    @Test
    void crashFiresExactlyAtTheConfiguredReading() {
        FailureInjector injector = new FailureInjector(FailureMode.CRASH, 30L, 1);

        assertFalse(injector.shouldCrash(29L), "must survive its 30th reading");
        assertTrue(injector.shouldCrash(30L), "must die once 30 readings are published");
        assertTrue(injector.shouldCrash(31L));
    }

    @Test
    void floodMultipliesOnlyAfterTheTrigger() {
        FailureInjector injector = new FailureInjector(FailureMode.MESSAGE_FLOOD, 10L, 8);

        assertEquals(1, injector.publishesForTick(9L));
        assertEquals(8, injector.publishesForTick(10L));
        assertEquals(8, injector.publishesForTick(500L));
    }

    @Test
    void floodDoesNotAlsoCrash() {
        FailureInjector injector = new FailureInjector(FailureMode.MESSAGE_FLOOD, 10L, 8);

        assertFalse(injector.shouldCrash(500L));
    }
}
