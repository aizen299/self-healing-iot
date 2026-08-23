package io.fleet.common;

/**
 * Supplies a {@link TelemetrySink} per device and owns their lifetime.
 *
 * <p>Devices get a sink each rather than sharing one because an MQTT Last
 * Will and Testament is a property of a <em>connection</em>. One shared
 * client would give the entire fleet a single will, so the broker could
 * announce only "the fleet went away" and never "device-017 went away" —
 * which is the signal Phase 4's failure detection is built on. Per-device
 * connections also mean a simulated network fault can take down one device
 * without touching its neighbours.
 *
 * <p>The factory closes what it created; callers close the factory. A sink
 * handed to a device is never closed by that device.
 */
public interface TelemetrySinkFactory extends AutoCloseable {

    /**
     * Returns the sink for {@code deviceId}. Implementations may return a
     * shared instance when the transport has no per-device state.
     *
     * @throws SinkException if the sink could not be established
     */
    TelemetrySink create(String deviceId) throws SinkException;

    /** Payloads accepted across every sink this factory produced. */
    long payloadCount();

    /** Bytes accepted across every sink this factory produced. */
    long byteCount();

    @Override
    void close() throws SinkException;
}
