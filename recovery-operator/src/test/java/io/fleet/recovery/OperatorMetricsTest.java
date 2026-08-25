package io.fleet.recovery;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator's scrape endpoint, over a real socket.
 *
 * <p>The claim worth testing is not that the numbers are rendered — it is that
 * the endpoint answers at all when the operator is unhealthy. An exporter that
 * failed with the thing it reports on would go dark exactly when someone
 * looked at it.
 */
class OperatorMetricsTest {

    private static final long DETECTED_AT = 1_787_500_000_000L;

    private FakeKubernetesApi cluster;
    private RecoveryController controller;
    private FailureConsumer consumer;
    private RecoveryPublisher publisher;
    private MetricsServer server;
    private HttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new FakeKubernetesApi();
        cluster.addPod("edge-device-002", "Failed",
                Map.of("app", "edge-device", "device-id", "device-002"),
                """
                {"metadata":{"name":"edge-device-002","labels":{"app":"edge-device",
                  "device-id":"device-002"}},
                 "spec":{"containers":[{"name":"device","env":[
                   {"name":"FLEET_DEVICE_INDEX_OFFSET","value":"1"}]}]}}
                """);
        controller = new RecoveryController(cluster, new ReplacementFactory(),
                OperatorConfig.from(Map.of()),
                Clock.fixed(Instant.ofEpochMilli(DETECTED_AT + 800), ZoneOffset.UTC));
        publisher = new RecoveryPublisher(
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()));
        consumer = new FailureConsumer(new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                controller, publisher, Duration.ofMillis(10L));

        server = new MetricsServer(0,
                new OperatorMetricsExporter(controller, consumer, publisher)::render);
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private String scrape() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.port() + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("text/plain; version=0.0.4; charset=utf-8",
                response.headers().firstValue("Content-Type").orElse(""));
        return response.body();
    }

    @Test
    @DisplayName("a fresh operator scrapes clean, with every outcome at zero")
    void reportsZeroesBeforeAnythingHappens() {
        // Series that only appear once they are non-zero make a healthy
        // operator indistinguishable from a broken scrape.
        String text = assertDoesNotThrowScrape();

        for (String outcome : new String[] {
                "REPLACED", "ALREADY_RECOVERED", "NOT_NEEDED", "FAILED" }) {
            assertTrue(text.contains("fleet_operator_recoveries_total{outcome=\""
                    + outcome + "\"} 0"), outcome + " missing from: " + text);
        }
    }

    @Test
    @DisplayName("a replacement shows up as an outcome and a duration")
    void countsWhatTheControllerDid() throws Exception {
        controller.onFailure(failure("device-002"));

        String text = scrape();

        assertTrue(text.contains("fleet_operator_recoveries_total{outcome=\"REPLACED\"} 1"),
                text);
        assertTrue(text.contains("fleet_operator_detection_to_replacement_millis_count 1"),
                text);
        assertTrue(text.contains("fleet_operator_detection_to_replacement_millis_sum 800"),
                text);
    }

    @Test
    @DisplayName("a redelivered failure creates nothing and is counted apart")
    void distinguishesADuplicate() throws Exception {
        controller.onFailure(failure("device-002"));
        controller.onFailure(failure("device-002"));

        String text = scrape();

        assertTrue(text.contains("fleet_operator_recoveries_total{outcome=\"REPLACED\"} 1"),
                text);
        assertTrue(text.contains(
                "fleet_operator_recoveries_total{outcome=\"ALREADY_RECOVERED\"} 1"), text);
        // The second event answered the same failure, so it measured nothing.
        assertTrue(text.contains("fleet_operator_detection_to_replacement_millis_count 1"),
                text);
    }

    @Test
    @DisplayName("the endpoint never claims to be MTTR")
    void doesNotNameItselfMttr() throws Exception {
        // The gateway's fleet_recovery_duration_millis already contains this
        // number. A panel that added them would roughly double the reported
        // MTTR, so the name has to make the mistake hard.
        String text = scrape();

        // No metric *name* may claim it. The HELP text says "MTTR" on purpose
        // — that line is where a dashboard author reads the warning — so the
        // check is on the names, which is what a PromQL query reaches for.
        text.lines()
                .filter(line -> line.startsWith("# TYPE "))
                .map(line -> line.split(" ")[2])
                .forEach(name -> assertFalse(
                        name.toLowerCase(java.util.Locale.ROOT).contains("mttr"),
                        "a metric named for MTTR invites the addition: " + name));

        assertTrue(text.contains("fleet_operator_detection_to_replacement_millis"), text);
        assertTrue(text.contains("must not be added"),
                "the HELP line is where a dashboard author reads this: " + text);
    }

    @Test
    @DisplayName("the JVM series match the gateway's names, so one panel shows both")
    void exportsTheSameJvmNamesAsTheGateway() throws Exception {
        // The dashboard draws gateway and operator on the same axes, separated
        // by the `component` label Prometheus attaches. Different names here
        // would mean two panels, or one panel with a series missing.
        String text = scrape();

        for (String name : new String[] {
                "fleet_jvm_heap_used_bytes", "fleet_jvm_heap_max_bytes",
                "fleet_jvm_threads" }) {
            assertTrue(text.contains("# TYPE " + name + " gauge"), name + ": " + text);
        }
    }

    @Test
    @DisplayName("anything other than GET is refused")
    void refusesNonGet() throws Exception {
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.port() + "/metrics"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(405, response.statusCode());
    }

    private String assertDoesNotThrowScrape() {
        try {
            return scrape();
        } catch (Exception e) {
            throw new AssertionError("the scrape endpoint must always answer", e);
        }
    }

    private static DeviceEventRecord failure(String deviceId) {
        return new DeviceEventRecord(deviceId, DeviceEventType.DEVICE_OFFLINE,
                DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE, DETECTED_AT, 4, -1L);
    }
}
