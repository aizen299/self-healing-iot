package io.fleet.edge.mqtt;

import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.common.TelemetrySinkFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Creates and owns one {@link MqttTelemetrySink} per device.
 *
 * <p>Counters are held here rather than per sink so the run summary reports
 * fleet totals across every connection.
 */
public final class MqttSinkFactory implements TelemetrySinkFactory {

    private final MqttConfig config;
    private final long interruptAfterPublishes;
    private final long interruptDurationMillis;

    private final Map<String, MqttTelemetrySink> created = new ConcurrentHashMap<>();
    private final LongAdder payloads = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder connectionLosses = new LongAdder();

    public MqttSinkFactory(MqttConfig config) {
        this(config, 0L, 0L);
    }

    /**
     * @param interruptAfterPublishes publishes before a simulated network loss,
     *                                or 0 to never inject one
     * @param interruptDurationMillis how long each device stays offline
     */
    public MqttSinkFactory(
            MqttConfig config, long interruptAfterPublishes, long interruptDurationMillis) {
        this.config = config;
        this.interruptAfterPublishes = interruptAfterPublishes;
        this.interruptDurationMillis = interruptDurationMillis;
    }

    @Override
    public TelemetrySink create(String deviceId) throws SinkException {
        MqttTelemetrySink sink = new MqttTelemetrySink(
                deviceId, config, interruptAfterPublishes, interruptDurationMillis,
                payloads, bytes, connectionLosses);
        // Registered before connecting so a partially built fleet still gets
        // torn down: without this, a broker that refuses the tenth connection
        // would strand the nine already established.
        created.put(deviceId, sink);
        sink.connect();
        return sink;
    }

    /** Drops a dead device's connection ungracefully, so the broker fires its will. */
    @Override
    public void abandon(String deviceId) throws SinkException {
        MqttTelemetrySink sink = created.remove(deviceId);
        if (sink != null) {
            sink.abort();
        }
    }

    @Override
    public long payloadCount() {
        return payloads.sum();
    }

    @Override
    public long byteCount() {
        return bytes.sum();
    }

    @Override
    public long connectionLosses() {
        return connectionLosses.sum();
    }

    /** Closes every sink, reporting the first failure only after trying them all. */
    @Override
    public void close() throws SinkException {
        SinkException firstFailure = null;
        for (MqttTelemetrySink sink : created.values()) {
            try {
                sink.close();
            } catch (SinkException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        created.clear();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
