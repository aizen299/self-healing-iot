package io.fleet.common;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The wire format for a health transition, both directions.
 *
 * <p>Here rather than in the gateway for the same reason
 * {@link TelemetryParser} is: the format has a second reader. Phase 9's
 * recovery operator consumes {@code device.failures}, and a format with one
 * writer and two independently-written readers is a format that drifts.
 *
 * <p>Not thread-safe. Jackson's {@code JsonFactory} is, but an instance of
 * this holds one, and callers are expected to keep one per thread — the
 * gateway forwards from a single sender thread and the operator consumes from
 * a single poll loop.
 */
public final class DeviceEventCodec {

    private final JsonFactory json = new JsonFactory();

    public byte[] encode(DeviceEventRecord event) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(224);
        try (JsonGenerator generator = json.createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField("deviceId", event.deviceId());
            generator.writeStringField("event", event.event().name());
            generator.writeStringField("from", event.fromHealth().name());
            generator.writeStringField("to", event.toHealth().name());
            generator.writeNumberField("at", event.atMillis());
            generator.writeNumberField("missedHeartbeats", event.missedHeartbeats());
            generator.writeNumberField("recoveryDurationMillis", event.recoveryDurationMillis());
            generator.writeEndObject();
        }
        // toByteArray, not toString().getBytes(): the round trip through a
        // String costs two conversions and is silently lossy for anything
        // that is not valid UTF-8.
        return out.toByteArray();
    }

    /**
     * Reads an event, rejecting anything it cannot fully understand.
     *
     * <p>Every field is required. A partially-parsed failure event is the one
     * kind this system must never act on optimistically: a missing
     * {@code deviceId} would name no device and a missing {@code at} would
     * make the recovery id — and therefore the idempotency key — depend on
     * when the message happened to be read.
     */
    public DeviceEventRecord decode(byte[] payload) throws MalformedPayloadException {
        String deviceId = null;
        DeviceEventType event = null;
        DeviceHealth from = null;
        DeviceHealth to = null;
        Long at = null;
        Integer missedHeartbeats = null;
        Long recoveryDurationMillis = null;

        try (JsonParser parser = json.createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MalformedPayloadException("event payload is not a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "deviceId" -> deviceId = parser.getText();
                    case "event" -> event = parseEnum(DeviceEventType.class, parser.getText());
                    case "from" -> from = parseEnum(DeviceHealth.class, parser.getText());
                    case "to" -> to = parseEnum(DeviceHealth.class, parser.getText());
                    case "at" -> at = parser.getLongValue();
                    case "missedHeartbeats" -> missedHeartbeats = parser.getIntValue();
                    case "recoveryDurationMillis" -> recoveryDurationMillis = parser.getLongValue();
                    // An unknown field is a newer writer, not a broken one.
                    // Skipping it lets a field be added without every reader
                    // having to be redeployed first.
                    default -> parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new MalformedPayloadException("event payload is not valid JSON: "
                    + e.getMessage(), e);
        }

        require(deviceId != null && !deviceId.isBlank(), "deviceId");
        require(event != null, "event");
        require(from != null, "from");
        require(to != null, "to");
        require(at != null, "at");
        require(missedHeartbeats != null, "missedHeartbeats");
        require(recoveryDurationMillis != null, "recoveryDurationMillis");

        return new DeviceEventRecord(deviceId, event, from, to, at,
                missedHeartbeats, recoveryDurationMillis);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value)
            throws MalformedPayloadException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new MalformedPayloadException(
                    "unknown " + type.getSimpleName() + ": " + value, e);
        }
    }

    private static void require(boolean present, String field)
            throws MalformedPayloadException {
        if (!present) {
            throw new MalformedPayloadException("event payload is missing " + field);
        }
    }
}
