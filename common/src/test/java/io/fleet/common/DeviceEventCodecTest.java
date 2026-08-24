package io.fleet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The health-transition wire format, both directions.
 *
 * <p>The round trip is the point. The gateway writes these and the recovery
 * operator acts on them, so a format that encodes one thing and decodes
 * another would have the operator recovering a device the gateway never
 * declared failed.
 */
class DeviceEventCodecTest {

    private final DeviceEventCodec codec = new DeviceEventCodec();

    @Test
    @DisplayName("an event survives the round trip unchanged")
    void roundTrips() throws Exception {
        DeviceEventRecord original = new DeviceEventRecord("device-002",
                DeviceEventType.DEVICE_OFFLINE, DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE,
                1_787_500_000_000L, 4, -1L);

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    @DisplayName("a recovery's duration survives, since MTTR is computed from it")
    void carriesRecoveryDuration() throws Exception {
        DeviceEventRecord recovered = new DeviceEventRecord("device-007",
                DeviceEventType.DEVICE_RECOVERED, DeviceHealth.RECOVERING, DeviceHealth.ONLINE,
                1_787_500_012_500L, 0, 12_500L);

        assertEquals(12_500L, codec.decode(codec.encode(recovered)).recoveryDurationMillis());
    }

    @Test
    @DisplayName("the encoding is the one the gateway has always written")
    void encodesTheEstablishedFieldNames() throws Exception {
        // Pinned deliberately. This codec was lifted out of the gateway when
        // the operator became a second reader; changing a field name here
        // would silently break anything already consuming the topic.
        String json = new String(codec.encode(new DeviceEventRecord("device-001",
                DeviceEventType.DEVICE_OFFLINE, DeviceHealth.ONLINE, DeviceHealth.OFFLINE,
                42L, 3, -1L)), StandardCharsets.UTF_8);

        assertEquals("{\"deviceId\":\"device-001\",\"event\":\"DEVICE_OFFLINE\","
                + "\"from\":\"ONLINE\",\"to\":\"OFFLINE\",\"at\":42,"
                + "\"missedHeartbeats\":3,\"recoveryDurationMillis\":-1}", json);
    }

    @Test
    @DisplayName("an unknown field is skipped, not rejected")
    void toleratesAFieldFromANewerWriter() throws Exception {
        // A reader that refused would have to be redeployed before the writer
        // could ever add anything.
        byte[] payload = ("{\"deviceId\":\"device-001\",\"event\":\"DEVICE_OFFLINE\","
                + "\"from\":\"ONLINE\",\"to\":\"OFFLINE\",\"at\":42,\"missedHeartbeats\":3,"
                + "\"recoveryDurationMillis\":-1,\"somethingNew\":{\"a\":[1,2]}}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("device-001", codec.decode(payload).deviceId());
    }

    @Test
    @DisplayName("a missing field is refused rather than defaulted")
    void refusesAnIncompleteEvent() {
        // `at` is the idempotency key's other half: defaulting it would make a
        // recovery id depend on when the message happened to be read, so the
        // same failure read twice would produce two replacements.
        byte[] noTimestamp = ("{\"deviceId\":\"device-001\",\"event\":\"DEVICE_OFFLINE\","
                + "\"from\":\"ONLINE\",\"to\":\"OFFLINE\",\"missedHeartbeats\":3,"
                + "\"recoveryDurationMillis\":-1}").getBytes(StandardCharsets.UTF_8);

        assertTrue(assertThrows(MalformedPayloadException.class,
                () -> codec.decode(noTimestamp)).getMessage().contains("at"));
    }

    @Test
    @DisplayName("garbage and unknown enum values are refused")
    void refusesWhatItCannotUnderstand() {
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode("{not json".getBytes(StandardCharsets.UTF_8)));
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode("[]".getBytes(StandardCharsets.UTF_8)));

        byte[] unknownEvent = ("{\"deviceId\":\"device-001\",\"event\":\"DEVICE_EXPLODED\","
                + "\"from\":\"ONLINE\",\"to\":\"OFFLINE\",\"at\":42,\"missedHeartbeats\":3,"
                + "\"recoveryDurationMillis\":-1}").getBytes(StandardCharsets.UTF_8);
        assertTrue(assertThrows(MalformedPayloadException.class,
                () -> codec.decode(unknownEvent)).getMessage().contains("DEVICE_EXPLODED"));
    }

    @Test
    @DisplayName("a blank device id is refused")
    void refusesANamelessDevice() {
        byte[] blank = ("{\"deviceId\":\"\",\"event\":\"DEVICE_OFFLINE\",\"from\":\"ONLINE\","
                + "\"to\":\"OFFLINE\",\"at\":42,\"missedHeartbeats\":3,"
                + "\"recoveryDurationMillis\":-1}").getBytes(StandardCharsets.UTF_8);

        assertThrows(MalformedPayloadException.class, () -> codec.decode(blank));
    }
}
