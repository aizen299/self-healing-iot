package io.fleet.common;

import java.util.List;

/**
 * Durable history of what the fleet did.
 *
 * <p>The seam that keeps the storage engine replaceable. Phase 5 supplies an
 * embedded implementation because Docker does not arrive until Phase 7;
 * a server-backed time-series database can be added behind this interface
 * then, without the gateway changing.
 *
 * <p>The query set is not generic on purpose. These are the questions the
 * research actually asks — device history, fleet aggregates, failures, and
 * recovery timings for Pillar B — and naming them here means the schema can
 * be indexed for the queries that exist rather than for queries someone
 * might invent.
 *
 * <p>Writes may be buffered; {@link #flush()} makes everything durable.
 * Anything reporting a result must flush first, or it may read a history that
 * is still partly in memory.
 *
 * <p>A store may lose writes without the gateway stopping — that is the
 * deliberate priority, since losing failure detection is worse than losing
 * history. {@link #integrity(long, long)} is how a caller finds out, and
 * anything reporting a figure must check it: a window that is not complete
 * cannot support a result.
 */
public interface TelemetryStore extends AutoCloseable {

    /** Records one accepted reading. May be buffered until {@link #flush()}. */
    void record(Telemetry telemetry, long receivedAtMillis) throws StoreException;

    /**
     * Records a health transition.
     *
     * <p>Written through immediately rather than buffered. Failures are rare,
     * individually meaningful, and the input to MTTR — losing one to a crash
     * would cost far more than losing a reading.
     */
    void recordEvent(DeviceEventRecord event) throws StoreException;

    /** Makes all buffered writes durable. */
    void flush() throws StoreException;

    /** Readings for one device within a time range, oldest first. */
    List<Telemetry> history(String deviceId, long fromMillis, long toMillis)
            throws StoreException;

    /** Mean temperature across the fleet in a window, or empty if no readings. */
    java.util.OptionalDouble fleetAverageTemperature(long fromMillis, long toMillis)
            throws StoreException;

    /** Readings per second across the fleet in a window. */
    double telemetryRate(long fromMillis, long toMillis) throws StoreException;

    /** Devices with an unresolved failure, most recent first. */
    List<String> currentlyFailedDevices() throws StoreException;

    /** Recovery events in a window, oldest first. */
    List<DeviceEventRecord> recoveries(long fromMillis, long toMillis) throws StoreException;

    /**
     * Mean recovery duration in a window, or empty if nothing recovered.
     *
     * <p>Pillar B's headline figure. Detection-to-confirmation, not
     * fault-to-recovery: the gateway cannot know when a device actually broke.
     */
    java.util.OptionalDouble meanRecoveryMillis(long fromMillis, long toMillis)
            throws StoreException;

    /** Readings stored for one device, for uptime and coverage checks. */
    long readingCount(String deviceId) throws StoreException;

    /**
     * Whether the history covering a window is known to be complete.
     *
     * <p>Reports only losses the store detected. It cannot know about data
     * that never reached it, so a complete window means "nothing was dropped
     * here", not "everything the fleet produced is here".
     */
    StoreIntegrity integrity(long fromMillis, long toMillis) throws StoreException;

    /** Readings this store has lost since it was opened. */
    long droppedWrites();

    /** Removes readings older than the cutoff; returns how many went. */
    int pruneTelemetryBefore(long cutoffMillis) throws StoreException;

    @Override
    void close() throws StoreException;
}
