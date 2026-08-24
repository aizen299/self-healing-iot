package io.fleet.gateway;

import io.fleet.common.DeviceStatus;
import io.fleet.common.Presence;
import io.fleet.common.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real HTTP server over a real socket on an ephemeral port, rather
 * than calling the handlers directly — status codes, headers, and JSON
 * encoding are part of what this class promises.
 */
class HealthApiTest {

    private DeviceRegistry registry;
    private GatewayMetrics metrics;
    private HealthApi api;
    private HttpClient http;
    private String baseUrl;
    private final HealthPolicy policy = new HealthPolicy(1_000L, 2, 4, 2);

    @BeforeEach
    void setUp() throws Exception {
        registry = new DeviceRegistry();
        metrics = new GatewayMetrics();
        GatewayConfig config = GatewayConfig.from(Map.of("GATEWAY_HTTP_PORT", "0"));
        api = new HealthApi(config, registry, metrics);
        api.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        baseUrl = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void tearDown() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    void healthReportsFleetCounters() throws Exception {
        registry.recordTelemetry(reading("device-001"), 1_000L);
        registry.recordPresence("device-001", Presence.ONLINE, 1_100L, policy);
        metrics.telemetryAccepted();
        metrics.telemetryMalformed();

        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        String body = response.body();
        assertTrue(body.contains("\"status\":\"UP\""), body);
        assertTrue(body.contains("\"devicesKnown\":1"), body);
        assertTrue(body.contains("\"devicesOnline\":1"), body);
        assertTrue(body.contains("\"telemetryAccepted\":1"), body);
        assertTrue(body.contains("\"telemetryMalformed\":1"), body);
    }

    @Test
    void devicesListsEveryKnownDevice() throws Exception {
        registry.recordTelemetry(reading("device-002"), 2_000L);
        registry.recordTelemetry(reading("device-001"), 1_000L);

        HttpResponse<String> response = get("/devices");

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"count\":2"), body);
        assertTrue(body.indexOf("device-001") < body.indexOf("device-002"),
                "devices must be listed in a stable order: " + body);
    }

    @Test
    void aSingleDeviceIncludesItsLastReading() throws Exception {
        registry.recordTelemetry(reading("device-001"), 1_000L);

        HttpResponse<String> response = get("/devices/device-001");

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"deviceId\":\"device-001\""), body);
        assertTrue(body.contains("\"lastTelemetry\""), body);
        assertTrue(body.contains("\"status\":\"OK\""), body);
        assertTrue(body.contains("\"presenceOnly\":false"), body);
    }

    @Test
    @DisplayName("a device known only from retained presence is flagged as such")
    void presenceOnlyDeviceIsFlagged() throws Exception {
        registry.recordPresence("ghost-001", Presence.OFFLINE, 500L, policy);

        String body = get("/devices/ghost-001").body();

        assertTrue(body.contains("\"presenceOnly\":true"), body);
        assertTrue(body.contains("\"lastTelemetry\":null"), body);
    }

    @Test
    void unknownDeviceIs404() throws Exception {
        HttpResponse<String> response = get("/devices/nobody");

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("unknown device"), response.body());
    }

    @Test
    @DisplayName("a path merely starting with /devices does not resolve to a device")
    void prefixPathIsNotTreatedAsADeviceLookup() throws Exception {
        registry.recordTelemetry(reading("oo"), 1_000L);

        // HttpServer matches contexts by prefix, so /devicesfoo reaches the
        // same handler; stripping a fixed character would look up "oo".
        HttpResponse<String> response = get("/devicesfoo");

        assertEquals(404, response.statusCode(), response.body());
        assertTrue(response.body().contains("not found"), response.body());
    }

    @Test
    void nonGetIsRejected() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Telemetry reading(String deviceId) {
        return new Telemetry(
                deviceId, 1_787_484_895_182L, 20.0d, 1.0d, 90.0d, 52.52d, 13.405d,
                DeviceStatus.OK);
    }

    @Test
    @DisplayName("/ready is 200 only while the broker connection is up")
    void readinessFollowsTheBrokerConnection() throws Exception {
        // A readiness probe on /health could never fail: it is unconditionally
        // 200 while the process lives, so a Kubernetes Service would keep
        // routing to a gateway that had lost the broker and was recording
        // nothing at all.
        java.util.concurrent.atomic.AtomicBoolean connected =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        GatewayConfig config = GatewayConfig.from(Map.of("GATEWAY_HTTP_PORT", "0"));
        try (HealthApi probeApi = new HealthApi(config, registry, metrics,
                new io.fleet.gateway.store.NoOpTelemetryStore(), connected::get)) {
            probeApi.start();
            String url = "http://127.0.0.1:" + probeApi.port();

            HttpResponse<String> down = http.send(
                    HttpRequest.newBuilder(URI.create(url + "/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, down.statusCode());
            assertTrue(down.body().contains("\"status\":\"NOT_READY\""), down.body());

            // Liveness is unaffected: a broker outage must not restart the
            // gateway, which still serves history and reconnects on its own.
            HttpResponse<String> live = http.send(
                    HttpRequest.newBuilder(URI.create(url + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertTrue(live.body().contains("\"brokerConnected\":false"), live.body());

            connected.set(true);
            HttpResponse<String> up = http.send(
                    HttpRequest.newBuilder(URI.create(url + "/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, up.statusCode());
            assertTrue(up.body().contains("\"status\":\"READY\""), up.body());
        }
    }

    @Test
    @DisplayName("an API built without a connection supplier fails closed")
    void readinessWithoutASupplierReportsNotReady() throws Exception {
        // The convenience constructors cannot see a broker, and the dangerous
        // default is the optimistic one: a caller that forgets the supplier
        // would get a /ready that can never return 503, quietly restoring the
        // defect the endpoint exists to fix while every probe still passes.
        HttpResponse<String> response = get("/ready");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"brokerConnected\":false"), response.body());
    }
}
