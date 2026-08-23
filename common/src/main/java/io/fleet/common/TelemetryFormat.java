package io.fleet.common;

/**
 * The telemetry wire format, shared by every producer and consumer.
 *
 * <p>Payload shape (field order is significant — it is part of the format):
 * <pre>
 * {"deviceId":"device-001","ts":1787480547123,"temp":21.40,"vib":0.42,
 *  "batt":87.30,"lat":52.5200,"lon":13.4050,"status":"OK"}
 * </pre>
 *
 * <p>The decimal precisions below are the reason this class exists. The
 * constrained and naive edge-device variants serialize by completely
 * different mechanisms — a hand-rolled fixed-point encoder writing into a
 * reused buffer, versus {@code String.format} — but ADR-003 requires their
 * output to be byte-identical, because otherwise the experiment compares
 * two different workloads instead of two implementations of one workload.
 * Both read their precision from here so the two can never drift apart
 * silently.
 */
public final class TelemetryFormat {

    /** Decimal places for temperature, vibration, and battery level. */
    public static final int SENSOR_DECIMALS = 2;

    /** Decimal places for latitude and longitude. */
    public static final int COORDINATE_DECIMALS = 4;

    private TelemetryFormat() {
    }
}
