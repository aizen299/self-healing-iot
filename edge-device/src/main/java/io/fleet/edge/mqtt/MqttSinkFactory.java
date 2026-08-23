package io.fleet.edge.mqtt;

import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.common.TelemetrySinkFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private final List<MqttTelemetrySink> created = new CopyOnWriteArrayList<>();
    private final LongAdder payloads = new LongAdder();
    private final LongAdder bytes = new LongAdder();

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
                payloads, bytes);
        // Registered before connecting so a partially built fleet still gets
        // torn down: without this, a broker that refuses the tenth connection
        // would strand the nine already established.
        created.add(sink);
        sink.connect();
        return sink;
    }

    @Override
    public long payloadCount() {
        return payloads.sum();
    }

    @Override
    public long byteCount() {
        return bytes.sum();
    }

    /** Closes every sink, reporting the first failure only after trying them all. */
    @Override
    public void close() throws SinkException {
        SinkException firstFailure = null;
        for (MqttTelemetrySink sink : created) {
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
