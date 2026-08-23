package io.fleet.integration;

import io.fleet.common.SensorModel;
import io.fleet.common.Telemetry;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.FailureInjector;
import io.fleet.edge.FailureMode;
import io.fleet.edge.constrained.ConstrainedEdgeDevice;
import io.fleet.edge.naive.NaiveEdgeDevice;
import io.fleet.edge.sink.RecordingSink;
import io.fleet.gateway.MalformedPayloadException;
import io.fleet.gateway.TelemetryParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The format-drift guard across the module boundary.
 *
 * <p>The device serializes with a hand-rolled fixed-point encoder and the
 * gateway parses with Jackson. Neither module's own tests would notice if the
 * two stopped agreeing, and the failure would present as devices apparently
 * going silent rather than as a format change.
 *
 * <p>Deliberately kept out of {@code MqttToGatewayTest}: this needs no broker,
 * and living beside tests that skip without one would mean the guard did not
 * run in the build most people execute — which is precisely when drift would
 * slip through.
 */
class WireFormatRoundTripTest {

    private static final int READINGS = 200;
    private static final long BASE_TIMESTAMP = 1_787_484_895_182L;

    private final TelemetryParser parser = new TelemetryParser();

    @Test
    @DisplayName("both device variants produce payloads the gateway parses identically")
    void deviceOutputRoundTripsThroughTheGatewayParser() throws Exception {
        RecordingSink constrainedSink = new RecordingSink();
        RecordingSink naiveSink = new RecordingSink();

        FailureInjector none = new FailureInjector(FailureMode.NONE, 0L, 1);
        EdgeDevice constrained =
                new ConstrainedEdgeDevice("device-001", sensor(), constrainedSink, none);
        EdgeDevice naive = new NaiveEdgeDevice("device-001", sensor(), naiveSink, none);

        for (int i = 0; i < READINGS; i++) {
            long timestamp = BASE_TIMESTAMP + i * 1_000L;
            constrained.publishReading(timestamp);
            naive.publishReading(timestamp);
        }

        List<String> fromConstrained = constrainedSink.payloads();
        List<String> fromNaive = naiveSink.payloads();

        for (int i = 0; i < READINGS; i++) {
            Telemetry parsedConstrained = parse(fromConstrained.get(i));
            Telemetry parsedNaive = parse(fromNaive.get(i));

            assertEquals(parsedNaive, parsedConstrained, "variants diverged at reading " + i);
            assertEquals("device-001", parsedConstrained.deviceId());
            assertEquals(BASE_TIMESTAMP + i * 1_000L, parsedConstrained.timestamp());
        }
    }

    private Telemetry parse(String payload) throws MalformedPayloadException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return parser.parse(bytes, 0, bytes.length);
    }

    private static SensorModel sensor() {
        return new SensorModel(1234L, 52.5200d, 13.4050d);
    }
}
