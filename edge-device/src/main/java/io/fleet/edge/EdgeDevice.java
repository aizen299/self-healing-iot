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

    /** Readings successfully published so far. */
    long readingsPublished();
}
