package io.fleet.common;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

/**
 * Reads the telemetry wire format back into a {@link Telemetry}.
 *
 * <p>Uses Jackson's streaming parser rather than databind: the format is
 * fixed and flat, so field-by-field handling costs little and gives precise
 * control over what counts as malformed — which matters because rejecting bad
 * input correctly is one of the gateway's jobs, not an afterthought.
 *
 * <p>Unknown fields are skipped, so a later phase can add a field without
 * every existing consumer failing. A known field that is missing, duplicated,
 * or carries a structured value is rejected: a reading that silently defaults
 * a temperature to zero, or quietly keeps the second of two, is worse than one
 * that never arrives.
 *
 * <p>Thread-safe: {@link JsonFactory} is, and no state is kept between calls.
 */
public final class TelemetryParser {

    private static final String[] FIELD_NAMES =
            {"deviceId", "ts", "temp", "vib", "batt", "lat", "lon", "status"};
    private static final int ALL_FIELDS = (1 << FIELD_NAMES.length) - 1;

    private final JsonFactory factory = new JsonFactory();

    public Telemetry parse(byte[] payload, int offset, int length)
            throws MalformedPayloadException {

        String deviceId = null;
        DeviceStatus status = null;
        long timestamp = 0L;
        double temperature = 0d;
        double vibration = 0d;
        double batteryLevel = 0d;
        double latitude = 0d;
        double longitude = 0d;
        int seen = 0;

        try (JsonParser parser = factory.createParser(payload, offset, length)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MalformedPayloadException("payload is not a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                if (field == null) {
                    throw new MalformedPayloadException("expected a field name");
                }
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "deviceId" -> {
                        seen = mark(seen, 0, parser, value);
                        deviceId = parser.getValueAsString();
                    }
                    case "ts" -> {
                        seen = mark(seen, 1, parser, value);
                        timestamp = parser.getLongValue();
                    }
                    case "temp" -> {
                        seen = mark(seen, 2, parser, value);
                        temperature = parser.getDoubleValue();
                    }
                    case "vib" -> {
                        seen = mark(seen, 3, parser, value);
                        vibration = parser.getDoubleValue();
                    }
                    case "batt" -> {
                        seen = mark(seen, 4, parser, value);
                        batteryLevel = parser.getDoubleValue();
                    }
                    case "lat" -> {
                        seen = mark(seen, 5, parser, value);
                        latitude = parser.getDoubleValue();
                    }
                    case "lon" -> {
                        seen = mark(seen, 6, parser, value);
                        longitude = parser.getDoubleValue();
                    }
                    case "status" -> {
                        seen = mark(seen, 7, parser, value);
                        status = parseStatus(parser.getValueAsString());
                    }
                    // Unknown fields are skipped whole, structure included, so
                    // the cursor stays on the top-level object.
                    default -> parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new MalformedPayloadException("payload is not valid JSON: " + e.getMessage(), e);
        }

        if (seen != ALL_FIELDS) {
            throw new MalformedPayloadException("payload is missing fields: " + missing(seen));
        }
        if (deviceId == null) {
            throw new MalformedPayloadException("deviceId is null");
        }
        return new Telemetry(
                deviceId, timestamp, temperature, vibration, batteryLevel,
                latitude, longitude, status);
    }

    /**
     * Records that a known field was seen, rejecting a repeat and rejecting a
     * structured value.
     *
     * <p>The structure check is the important one. Without it, reading an
     * object-valued field with a scalar getter leaves the cursor <em>inside</em>
     * that object, and its keys are then consumed as though they were
     * top-level — so {@code {"deviceId":{"deviceId":"x","ts":1,…}}} would parse
     * into a complete, entirely fabricated reading.
     */
    private static int mark(int seen, int index, JsonParser parser, JsonToken value)
            throws MalformedPayloadException, IOException {

        if ((seen & (1 << index)) != 0) {
            throw new MalformedPayloadException(
                    "duplicate field '" + FIELD_NAMES[index] + "'");
        }
        if (value == null || value.isStructStart()) {
            parser.skipChildren();
            throw new MalformedPayloadException(
                    "field '" + FIELD_NAMES[index] + "' must be a scalar value");
        }
        return seen | (1 << index);
    }

    private static DeviceStatus parseStatus(String raw) throws MalformedPayloadException {
        if (raw == null) {
            throw new MalformedPayloadException("status is null");
        }
        try {
            return DeviceStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new MalformedPayloadException("unknown status '" + raw + "'", e);
        }
    }

    private static String missing(int seen) {
        StringBuilder absent = new StringBuilder();
        for (int i = 0; i < FIELD_NAMES.length; i++) {
            if ((seen & (1 << i)) == 0) {
                if (absent.length() > 0) {
                    absent.append(", ");
                }
                absent.append(FIELD_NAMES[i]);
            }
        }
        return absent.toString();
    }
}
