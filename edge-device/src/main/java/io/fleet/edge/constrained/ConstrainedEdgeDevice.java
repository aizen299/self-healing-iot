package io.fleet.edge.constrained;

import io.fleet.common.SensorModel;
import io.fleet.common.SinkException;
import io.fleet.common.TelemetryFormat;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import io.fleet.edge.DeviceCrashedException;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.FailureInjector;

/**
 * Resource-disciplined device: steady-state publishing allocates nothing.
 *
 * <p>Everything variable is established once in the constructor — the topic
 * string, the device id as bytes, the payload buffer, the sensor model — and
 * {@link #publishReading(long)} then reuses all of it. Compared with
 * {@code io.fleet.edge.naive.NaiveEdgeDevice}, which performs the same work
 * conventionally, the differences are:
 *
 * <ul>
 *   <li>no {@code Telemetry} object per reading</li>
 *   <li>no {@code String.format}, and so no {@code Formatter}, no intermediate
 *       {@code String}, and no boxing of the {@code double} and {@code long}
 *       arguments into a varargs array</li>
 *   <li>no {@code String.getBytes} copy</li>
 *   <li>the topic built once rather than per reading</li>
 *   <li>a bounded buffer that fails loudly instead of growing</li>
 * </ul>
 *
 * <p>Both variants emit byte-identical payloads; see ADR-003.
 */
public final class ConstrainedEdgeDevice implements EdgeDevice {

    /** Generous for the documented format; overflow throws rather than grows. */
    static final int PAYLOAD_CAPACITY = 256;

    private static final byte[] HEAD = PayloadBuffer.ascii("{\"deviceId\":\"");
    private static final byte[] TS = PayloadBuffer.ascii("\",\"ts\":");
    private static final byte[] TEMP = PayloadBuffer.ascii(",\"temp\":");
    private static final byte[] VIB = PayloadBuffer.ascii(",\"vib\":");
    private static final byte[] BATT = PayloadBuffer.ascii(",\"batt\":");
    private static final byte[] LAT = PayloadBuffer.ascii(",\"lat\":");
    private static final byte[] LON = PayloadBuffer.ascii(",\"lon\":");
    private static final byte[] STATUS = PayloadBuffer.ascii(",\"status\":\"");
    private static final byte[] TAIL = PayloadBuffer.ascii("\"}");

    private final String deviceId;
    private final byte[] deviceIdBytes;
    private final String topic;
    private final SensorModel sensor;
    private final TelemetrySink sink;
    private final FailureInjector failures;
    private final PayloadBuffer payload = new PayloadBuffer(PAYLOAD_CAPACITY);

    private long readingsPublished;

    public ConstrainedEdgeDevice(
            String deviceId, SensorModel sensor, TelemetrySink sink, FailureInjector failures) {
        this.deviceId = deviceId;
        this.deviceIdBytes = PayloadBuffer.ascii(deviceId);
        this.topic = Topics.telemetry(deviceId);
        this.sensor = sensor;
        this.sink = sink;
        this.failures = failures;
    }

    @Override
    public String deviceId() {
        return deviceId;
    }

    @Override
    public void publishReading(long nowMillis) throws SinkException {
        if (failures.shouldCrash(readingsPublished)) {
            throw new DeviceCrashedException(deviceId, readingsPublished);
        }
        int publishes = failures.publishesForTick(readingsPublished);
        for (int i = 0; i < publishes; i++) {
            sensor.advance();
            encode(nowMillis);
            sink.publish(topic, payload.array(), 0, payload.length());
            readingsPublished++;
        }
    }

    /** Writes the current sensor state into the reused buffer. */
    private void encode(long nowMillis) {
        payload.reset()
                .raw(HEAD)
                .raw(deviceIdBytes)
                .raw(TS)
                .number(nowMillis)
                .raw(TEMP)
                .fixed(sensor.temperature(), TelemetryFormat.SENSOR_DECIMALS)
                .raw(VIB)
                .fixed(sensor.vibration(), TelemetryFormat.SENSOR_DECIMALS)
                .raw(BATT)
                .fixed(sensor.batteryLevel(), TelemetryFormat.SENSOR_DECIMALS)
                .raw(LAT)
                .fixed(sensor.latitude(), TelemetryFormat.COORDINATE_DECIMALS)
                .raw(LON)
                .fixed(sensor.longitude(), TelemetryFormat.COORDINATE_DECIMALS)
                .raw(STATUS)
                .text(sensor.status().name())
                .raw(TAIL);
    }

    @Override
    public long readingsPublished() {
        return readingsPublished;
    }
}
