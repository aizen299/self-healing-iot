package io.fleet.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.fleet.common.Heartbeat;

import java.io.IOException;

/**
 * Reads the heartbeat wire format.
 *
 * <p>Separate from {@link TelemetryParser} rather than a generalisation of
 * it: the two share a shape but not a contract, and folding them together
 * would mean a change to the telemetry format could quietly alter how
 * liveness is decided.
 *
 * <p>Applies the same rules as the telemetry parser — unknown fields skipped,
 * missing or duplicated or structured known fields rejected — for the same
 * reason: a heartbeat that silently defaults is worse than one that never
 * arrives, because it asserts life on no evidence.
 */
public final class HeartbeatParser {

    private static final String[] FIELD_NAMES = {"deviceId", "ts"};
    private static final int ALL_FIELDS = (1 << FIELD_NAMES.length) - 1;

    private final JsonFactory factory = new JsonFactory();

    public Heartbeat parse(byte[] payload, int offset, int length)
            throws MalformedPayloadException {

        String deviceId = null;
        long timestamp = 0L;
        int seen = 0;

        try (JsonParser parser = factory.createParser(payload, offset, length)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MalformedPayloadException("heartbeat is not a JSON object");
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
                    default -> parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new MalformedPayloadException(
                    "heartbeat is not valid JSON: " + e.getMessage(), e);
        }

        if (seen != ALL_FIELDS) {
            throw new MalformedPayloadException("heartbeat is missing fields: " + missing(seen));
        }
        if (deviceId == null) {
            throw new MalformedPayloadException("heartbeat deviceId is null");
        }
        return new Heartbeat(deviceId, timestamp);
    }

    private static int mark(int seen, int index, JsonParser parser, JsonToken value)
            throws MalformedPayloadException, IOException {

        if ((seen & (1 << index)) != 0) {
            throw new MalformedPayloadException("duplicate field '" + FIELD_NAMES[index] + "'");
        }
        if (value == null || value.isStructStart()) {
            parser.skipChildren();
            throw new MalformedPayloadException(
                    "field '" + FIELD_NAMES[index] + "' must be a scalar value");
        }
        return seen | (1 << index);
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
