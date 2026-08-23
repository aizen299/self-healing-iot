package io.fleet.gateway;

import io.fleet.common.DeviceStatus;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryParserTest {

    private static final String VALID =
            "{\"deviceId\":\"device-001\",\"ts\":1787484895182,\"temp\":19.90,\"vib\":1.01,"
                    + "\"batt\":99.95,\"lat\":52.5235,\"lon\":13.4083,\"status\":\"OK\"}";

    private final TelemetryParser parser = new TelemetryParser();

    @Test
    void parsesTheDocumentedFormat() throws Exception {
        Telemetry telemetry = parse(VALID);

        assertEquals("device-001", telemetry.deviceId());
        assertEquals(1_787_484_895_182L, telemetry.timestamp());
        assertEquals(19.90d, telemetry.temperature(), 1e-9);
        assertEquals(1.01d, telemetry.vibration(), 1e-9);
        assertEquals(99.95d, telemetry.batteryLevel(), 1e-9);
        assertEquals(52.5235d, telemetry.latitude(), 1e-9);
        assertEquals(13.4083d, telemetry.longitude(), 1e-9);
        assertEquals(DeviceStatus.OK, telemetry.status());
    }

    @Test
    @DisplayName("parses only the requested slice of a larger buffer")
    void parsesASlice() throws Exception {
        byte[] body = VALID.getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[body.length + 32];
        System.arraycopy(body, 0, buffer, 4, body.length);
        // Trailing bytes are deliberate garbage; the parser must not read them.
        buffer[4 + body.length] = '!';

        assertEquals("device-001", parser.parse(buffer, 4, body.length).deviceId());
    }

    @Test
    @DisplayName("unknown fields are skipped so the format can grow")
    void toleratesUnknownFields() throws Exception {
        String withExtra = VALID.replace(
                "\"status\":\"OK\"", "\"firmware\":\"1.2.3\",\"nested\":{\"a\":1},\"status\":\"OK\"");

        assertEquals(DeviceStatus.OK, parse(withExtra).status());
    }

    @Test
    @DisplayName("a missing field is rejected rather than defaulted")
    void rejectsMissingFields() {
        String withoutTemp = VALID.replace("\"temp\":19.90,", "");

        MalformedPayloadException error =
                assertThrows(MalformedPayloadException.class, () -> parse(withoutTemp));
        assertTrue(error.getMessage().contains("temp"), error.getMessage());
    }

    @Test
    void rejectsNonJson() {
        assertThrows(MalformedPayloadException.class, () -> parse("not json at all"));
        assertThrows(MalformedPayloadException.class, () -> parse(""));
        assertThrows(MalformedPayloadException.class, () -> parse("[1,2,3]"));
    }

    @Test
    void rejectsTruncatedJson() {
        assertThrows(MalformedPayloadException.class,
                () -> parse("{\"deviceId\":\"device-001\",\"ts\":178748"));
    }

    @Test
    void rejectsAnUnknownStatus() {
        String badStatus = VALID.replace("\"OK\"", "\"EXPLODED\"");

        MalformedPayloadException error =
                assertThrows(MalformedPayloadException.class, () -> parse(badStatus));
        assertTrue(error.getMessage().contains("EXPLODED"), error.getMessage());
    }

    @Test
    void rejectsAWronglyTypedField() {
        String textTimestamp = VALID.replace("\"ts\":1787484895182", "\"ts\":\"yesterday\"");

        assertThrows(MalformedPayloadException.class, () -> parse(textTimestamp));
    }

    private Telemetry parse(String json) throws MalformedPayloadException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return parser.parse(payload, 0, payload.length);
    }
}
