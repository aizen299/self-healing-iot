package io.fleet.edge;

import io.fleet.common.SinkException;

/**
 * One simulated device.
 *
 * <p>Deliberately passive: a device does not own a thread or a clock. The
 * harness decides when {@link #publishReading(long)} runs and supplies the
 * timestamp. That keeps the threading policy a property of the variant
 * rather than of the device, and makes runs reproducible because the clock
 * can be driven by a test.
 */
public interface EdgeDevice {

    String deviceId();

    /**
     * Generates and publishes one reading.
     *
     * @param nowMillis timestamp to stamp the reading with
     * @throws SinkException           if the sink rejected the payload
     * @throws DeviceCrashedException  if a configured CRASH point was reached
     */
    void publishReading(long nowMillis) throws SinkException;

    /**
     * Publishes a liveness signal.
     *
     * <p>Called by the harness on the same tick as {@link #publishReading},
     * on the same thread and before it. Sharing the tick keeps all of a
     * device's work single-threaded: a separate heartbeat schedule would let
     * two pool threads touch one device at once, and guarding against that
     * would put a lock on the constrained variant's hot path — where it would
     * show up in the Pillar A measurements.
     *
     * <p>A no-op once {@code HEARTBEAT_STOP} has fired, and it throws
     * {@link DeviceCrashedException} at a configured CRASH point: a dead
     * device must not go on asserting that it is alive.
     */
    void publishHeartbeat(long nowMillis) throws SinkException;

    /** Readings successfully published so far. */
    long readingsPublished();

    /** Heartbeats successfully published so far. */
    long heartbeatsPublished();
}
