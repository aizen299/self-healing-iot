package io.fleet.edge.naive;

import io.fleet.common.SensorModel;
import io.fleet.common.SinkException;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryFormat;
import io.fleet.common.TelemetrySink;
import io.fleet.common.Topics;
import io.fleet.edge.DeviceCrashedException;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.FailureInjector;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Conventional device implementation — the experimental baseline.
 *
 * <p>This is deliberately not bad code. It is the straightforward,
 * idiomatic way a competent Java developer would write this without thinking
 * about allocation: build an object for the reading, format it with
 * {@code String.format}, convert to bytes, publish. Every line is normal
 * practice. That is the point of the comparison — the question the research
 * asks is what that ordinary style costs under a heap cap, not whether
 * obviously wasteful code is wasteful.
 *
 * <p>Per reading this allocates, at minimum: a {@link Telemetry} record, a
 * topic {@code String}, a varargs {@code Object[]} with six boxed primitives
 * inside it, a {@code Formatter} and its {@code StringBuilder}, the result
 * {@code String}, and the UTF-8 byte array.
 *
 * <p>Output is byte-identical to the constrained variant; see ADR-003.
 */
public final class NaiveEdgeDevice implements EdgeDevice {

    private static final String PAYLOAD_FORMAT = buildPayloadFormat();

    private final String deviceId;
    private final SensorModel sensor;
    private final TelemetrySink sink;
    private final FailureInjector failures;

    private long readingsPublished;

    public NaiveEdgeDevice(
            String deviceId, SensorModel sensor, TelemetrySink sink, FailureInjector failures) {
        this.deviceId = deviceId;
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

            Telemetry telemetry = new Telemetry(
                    deviceId,
                    nowMillis,
                    sensor.temperature(),
                    sensor.vibration(),
                    sensor.batteryLevel(),
                    sensor.latitude(),
                    sensor.longitude(),
                    sensor.status());

            String topic = Topics.telemetry(deviceId);
            String json = serialize(telemetry);
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);

            sink.publish(topic, payload, 0, payload.length);
            readingsPublished++;
        }
    }

    /**
     * Locale.ROOT is not incidental — under a locale with a comma decimal
     * separator the default would emit {@code 21,40} and produce invalid JSON.
     */
    static String serialize(Telemetry telemetry) {
        return String.format(
                Locale.ROOT,
                PAYLOAD_FORMAT,
                telemetry.deviceId(),
                telemetry.timestamp(),
                telemetry.temperature(),
                telemetry.vibration(),
                telemetry.batteryLevel(),
                telemetry.latitude(),
                telemetry.longitude(),
                telemetry.status().name());
    }

    /** Built from the shared spec so the two variants cannot drift apart. */
    private static String buildPayloadFormat() {
        int sensor = TelemetryFormat.SENSOR_DECIMALS;
        int coordinate = TelemetryFormat.COORDINATE_DECIMALS;
        return "{\"deviceId\":\"%s\""
                + ",\"ts\":%d"
                + ",\"temp\":%." + sensor + "f"
                + ",\"vib\":%." + sensor + "f"
                + ",\"batt\":%." + sensor + "f"
                + ",\"lat\":%." + coordinate + "f"
                + ",\"lon\":%." + coordinate + "f"
                + ",\"status\":\"%s\"}";
    }

    @Override
    public long readingsPublished() {
        return readingsPublished;
    }
}
