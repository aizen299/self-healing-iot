package io.fleet.gateway.kafka;

import io.fleet.common.DeviceEventRecord;

/**
 * Forwards nothing, used when Kafka is switched off.
 *
 * <p>A null object rather than a nullable field: forwarding sits on the path
 * every reading takes, and scattering null checks through it would be both
 * noise and an invitation to forget one.
 */
public final class NoOpForwarder implements TelemetryForwarder {

    @Override
    public void forwardTelemetry(String deviceId, byte[] payload, int offset, int length) {
    }

    @Override
    public void forwardEvent(DeviceEventRecord event) {
    }

    @Override
    public long forwardFailures() {
        return 0L;
    }

    @Override
    public void close() {
    }
}
