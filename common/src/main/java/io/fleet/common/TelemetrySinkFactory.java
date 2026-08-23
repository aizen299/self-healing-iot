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

    /**
     * Releases the sink for a device that has died, <em>without</em> a
     * graceful goodbye.
     *
     * <p>Distinct from {@link #close()} on purpose. A clean disconnect tells
     * the broker to suppress the Last Will, which is right for an orderly
     * shutdown and wrong for a crash: a device that loses power never gets to
     * say anything, and the will is the whole failure signal. Implementations
     * backed by a connection must drop it the way a dead process would.
     *
     * <p>No-op for transports with no per-device state.
     */
    void abandon(String deviceId) throws SinkException;

    /** Payloads accepted across every sink this factory produced. */
    long payloadCount();

    /** Bytes accepted across every sink this factory produced. */
    long byteCount();

    /**
     * Unexpected connection losses the transport observed — a broker restart
     * or a real network fault, as opposed to an injected one. Zero for
     * transports that hold no connection.
     */
    default long connectionLosses() {
        return 0L;
    }

    @Override
    void close() throws SinkException;
}
