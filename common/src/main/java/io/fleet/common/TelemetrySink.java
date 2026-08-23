package io.fleet.common;

/**
 * Destination for encoded telemetry payloads.
 *
 * <p>This is the seam that keeps Phase 2 from rewriting Phase 1. The
 * simulator publishes through this interface; Phase 1 supplies in-memory
 * implementations, and Phase 2 adds an MQTT-backed one behind the same
 * interface without touching device code.
 *
 * <p>The payload is passed as a byte range rather than a {@code String} or a
 * {@link Telemetry} so a caller can hand over a slice of a buffer it intends
 * to reuse. Implementations must therefore treat the array as valid only for
 * the duration of the call and must copy anything they retain.
 */
public interface TelemetrySink extends AutoCloseable {

    /**
     * Publishes one payload.
     *
     * @param topic   destination topic
     * @param payload buffer holding the encoded payload
     * @param offset  start of the payload within {@code payload}
     * @param length  payload length in bytes
     * @throws SinkException if the payload could not be accepted
     */
    void publish(String topic, byte[] payload, int offset, int length) throws SinkException;

    @Override
    void close() throws SinkException;
}
