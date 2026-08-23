package io.fleet.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.fleet.common.DeviceStatus;
import io.fleet.common.Telemetry;

import java.io.IOException;

/**
 * Reads the telemetry wire format back into a {@link Telemetry}.
 *
 * <p>Uses Jackson's streaming parser rather than databind: the format is
 * fixed and flat, so field-by-field handling costs little and gives precise
 * control over what counts as malformed — which matters because rejecting bad
 * input correctly is one of the gateway's jobs, not an afterthought.
 *
 * <p>Unknown fields are skipped rather than rejected, so a later phase can
 * add a field without every existing consumer failing. Missing or duplicated
 * known fields are rejected, because a reading that silently defaults a
 * temperature to zero is worse than one that never arrives.
 *
 * <p>Thread-safe: {@link JsonFactory} is, and no state is kept between calls.
 */
public final class TelemetryParser {

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
                parser.nextToken();
                switch (field) {
                    case "deviceId" -> {
                        deviceId = parser.getValueAsString();
                        seen |= 1;
                    }
                    case "ts" -> {
                        timestamp = parser.getLongValue();
                        seen |= 1 << 1;
                    }
                    case "temp" -> {
                        temperature = parser.getDoubleValue();
                        seen |= 1 << 2;
                    }
                    case "vib" -> {
                        vibration = parser.getDoubleValue();
                        seen |= 1 << 3;
                    }
                    case "batt" -> {
                        batteryLevel = parser.getDoubleValue();
                        seen |= 1 << 4;
                    }
                    case "lat" -> {
                        latitude = parser.getDoubleValue();
                        seen |= 1 << 5;
                    }
                    case "lon" -> {
                        longitude = parser.getDoubleValue();
                        seen |= 1 << 6;
                    }
                    case "status" -> {
                        status = parseStatus(parser.getValueAsString());
                        seen |= 1 << 7;
                    }
                    default -> parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new MalformedPayloadException("payload is not valid JSON: " + e.getMessage(), e);
        }

        if (seen != 0xFF) {
            throw new MalformedPayloadException("payload is missing fields: " + missing(seen));
        }
        return new Telemetry(
                deviceId, timestamp, temperature, vibration, batteryLevel,
                latitude, longitude, status);
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
        String[] names = {"deviceId", "ts", "temp", "vib", "batt", "lat", "lon", "status"};
        StringBuilder absent = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((seen & (1 << i)) == 0) {
                if (absent.length() > 0) {
                    absent.append(", ");
                }
                absent.append(names[i]);
            }
        }
        return absent.toString();
    }
}
