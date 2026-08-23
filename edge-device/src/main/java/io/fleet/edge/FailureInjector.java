package io.fleet.edge;

/**
 * Decides when a configured failure fires.
 *
 * <p>Shared by both variants on purpose. Failure timing is not part of what
 * Pillar A compares, so both must behave identically here; two separate
 * copies of this logic could drift and silently give one variant an easier
 * run than the other.
 *
 * <p>Allocation-free and deterministic: the trigger is a reading count, not
 * wall-clock time, so a run reproduces exactly regardless of host speed.
 */
public final class FailureInjector {

    private final FailureMode mode;
    private final long failAfterReadings;
    private final int floodMultiplier;

    public FailureInjector(FailureMode mode, long failAfterReadings, int floodMultiplier) {
        this.mode = mode;
        this.failAfterReadings = failAfterReadings;
        this.floodMultiplier = floodMultiplier;
    }

    public static FailureInjector from(DeviceConfig config) {
        return new FailureInjector(
                config.failureMode(), config.failAfterReadings(), config.floodMultiplier());
    }

    /** Whether the device should die before publishing its next reading. */
    public boolean shouldCrash(long readingsPublished) {
        return mode == FailureMode.CRASH && readingsPublished >= failAfterReadings;
    }

    /** How many readings this tick should emit. */
    public int publishesForTick(long readingsPublished) {
        if (mode == FailureMode.MESSAGE_FLOOD && readingsPublished >= failAfterReadings) {
            return floodMultiplier;
        }
        return 1;
    }

    public FailureMode mode() {
        return mode;
    }
}
