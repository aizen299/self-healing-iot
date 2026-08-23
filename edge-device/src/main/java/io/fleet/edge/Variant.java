package io.fleet.edge;

/**
 * The two implementations compared by Pillar A of the research.
 *
 * <p>The variant also selects the fleet's threading policy, because thread
 * discipline is one of the things under comparison — an unbounded
 * thread-per-device model is a resource decision, not an implementation
 * detail.
 */
public enum Variant {

    /** Object reuse, cached topics, hand-rolled encoding, one shared thread. */
    CONSTRAINED,

    /** Straightforward Java with no resource discipline; one thread per device. */
    NAIVE;

    /** Threads the harness allocates for a fleet of {@code deviceCount}. */
    public int threadCount(int deviceCount) {
        return this == CONSTRAINED ? 1 : deviceCount;
    }

    public static Variant parse(String raw) {
        return Variant.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
