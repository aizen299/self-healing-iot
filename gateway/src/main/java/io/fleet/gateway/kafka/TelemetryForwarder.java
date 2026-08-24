package io.fleet.gateway.kafka;

import io.fleet.common.DeviceEventRecord;

/**
 * Publishes what the gateway sees onto Kafka for downstream consumers.
 *
 * <p>An interface so the gateway does not depend on Kafka being present. The
 * gateway's own job — ingest, detect, persist — is complete without it, and
 * an unavailable broker must not stop any of that.
 *
 * <p>Implementations must not throw. A forwarding failure is counted and
 * reported; it never propagates into ingestion or detection, for the same
 * reason a store failure does not.
 */
public interface TelemetryForwarder extends AutoCloseable {

    /**
     * Forwards an accepted reading, as the exact bytes that arrived.
     *
     * <p>Not re-serialised. The MQTT payload is already the wire format, so
     * republishing it verbatim means the Kafka record and the MQTT message are
     * byte-identical — there is no second encoder that could drift from the
     * first, and no cost for having one.
     */
    void forwardTelemetry(String deviceId, byte[] payload, int offset, int length);

    /** Forwards a health transition to the event topics. */
    void forwardEvent(DeviceEventRecord event);

    /** Records the forwarder failed to deliver. */
    long forwardFailures();

    @Override
    void close();
}
