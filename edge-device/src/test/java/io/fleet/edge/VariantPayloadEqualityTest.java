package io.fleet.edge;

import io.fleet.common.SensorModel;
import io.fleet.common.SinkException;
import io.fleet.edge.constrained.ConstrainedEdgeDevice;
import io.fleet.edge.naive.NaiveEdgeDevice;
import io.fleet.edge.sink.RecordingSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fairness guarantee behind Pillar A.
 *
 * <p>ADR-003 requires the two variants to run an identical workload and emit
 * identical bytes, so that any measured difference is attributable to
 * implementation discipline alone. They serialize by entirely different
 * mechanisms — a hand-rolled fixed-point encoder against
 * {@code String.format} — so nothing but this test keeps them aligned.
 *
 * <p>If this test ever fails, every Pillar A measurement taken afterwards is
 * void: the two variants would be doing different work, and the comparison
 * would be meaningless rather than merely inaccurate.
 */
class VariantPayloadEqualityTest {

    private static final long BASE_TIMESTAMP = 1_787_480_547_123L;
    private static final int READINGS = 1_000;

    @Test
    @DisplayName("constrained and naive variants emit byte-identical payloads")
    void variantsProduceIdenticalPayloads() throws SinkException {
        RecordingSink constrainedSink = new RecordingSink();
        RecordingSink naiveSink = new RecordingSink();

        EdgeDevice constrained = new ConstrainedEdgeDevice(
                "device-001", sensorModel(), constrainedSink, noFailures());
        EdgeDevice naive = new NaiveEdgeDevice(
                "device-001", sensorModel(), naiveSink, noFailures());

        for (int i = 0; i < READINGS; i++) {
            long timestamp = BASE_TIMESTAMP + i * 1_000L;
            constrained.publishReading(timestamp);
            naive.publishReading(timestamp);
        }

        assertEquals(READINGS, constrainedSink.size(), "constrained reading count");
        assertEquals(READINGS, naiveSink.size(), "naive reading count");
        assertEquals(naiveSink.topics(), constrainedSink.topics(), "topics must match");

        // Snapshotted once. Each accessor copies the whole list, so calling
        // them inside the loop makes the comparison quadratic — which would
        // become the reason to shrink READINGS rather than raise it.
        List<String> naivePayloads = naiveSink.payloads();
        List<String> constrainedPayloads = constrainedSink.payloads();
        for (int i = 0; i < READINGS; i++) {
            assertEquals(
                    naivePayloads.get(i),
                    constrainedPayloads.get(i),
                    "payload " + i + " differs between variants");
        }
    }

    @Test
    @DisplayName("payload matches the documented wire format")
    void payloadMatchesDocumentedShape() throws SinkException {
        RecordingSink sink = new RecordingSink();
        EdgeDevice device =
                new ConstrainedEdgeDevice("device-007", sensorModel(), sink, noFailures());

        device.publishReading(BASE_TIMESTAMP);

        String payload = sink.payloads().get(0);
        assertEquals("fleet/device-007/telemetry", sink.topics().get(0));
        assertTrue(payload.startsWith("{\"deviceId\":\"device-007\",\"ts\":" + BASE_TIMESTAMP + ","),
                "unexpected payload head: " + payload);
        assertTrue(payload.endsWith("\"}"), "unexpected payload tail: " + payload);
        assertTrue(payload.matches(
                        "\\{\"deviceId\":\"device-007\",\"ts\":\\d+,"
                                + "\"temp\":-?\\d+\\.\\d{2},"
                                + "\"vib\":-?\\d+\\.\\d{2},"
                                + "\"batt\":-?\\d+\\.\\d{2},"
                                + "\"lat\":-?\\d+\\.\\d{4},"
                                + "\"lon\":-?\\d+\\.\\d{4},"
                                + "\"status\":\"(OK|DEGRADED|CRITICAL)\"\\}"),
                "payload does not match the documented format: " + payload);
    }

    private static SensorModel sensorModel() {
        return new SensorModel(1234L, 52.5200d, 13.4050d);
    }

    private static FailureInjector noFailures() {
        return new FailureInjector(FailureMode.NONE, 0L, 1);
    }
}
