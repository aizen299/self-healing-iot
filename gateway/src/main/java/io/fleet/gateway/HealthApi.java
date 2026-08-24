package io.fleet.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.fleet.common.StoreException;
import io.fleet.common.StoreIntegrity;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Read-only HTTP view of the gateway.
 *
 * <p>Built on the JDK's own {@code HttpServer} rather than a web framework.
 * Three read-only endpoints do not justify pulling a framework and its
 * dependency tree into a project whose subject is resource-conscious
 * engineering — and the gateway's memory footprint is something Phase 8
 * measures.
 *
 * <ul>
 *   <li>{@code GET /health} — liveness plus fleet-level counters</li>
 *   <li>{@code GET /ready} — readiness: 200 when connected to the broker,
 *       503 when not</li>
 *   <li>{@code GET /devices} — every known device</li>
 *   <li>{@code GET /devices/{id}} — one device, 404 if unknown</li>
 * </ul>
 */
public final class HealthApi implements AutoCloseable {

    private static final int STOP_DELAY_SECONDS = 1;

    /** Default /stats window when the caller gives no bounds. */
    private static final long DEFAULT_WINDOW_MILLIS = 5 * 60 * 1000L;

    /**
     * Cap on rows returned by /history.
     *
     * <p>The whole result is materialised and then serialised in memory before
     * a byte is written, and the gateway's footprint is something Phase 8
     * measures — an unbounded default would hold a device's entire history
     * twice in the heap of the process being measured.
     */
    private static final int MAX_HISTORY_ROWS = 5_000;

    private final DeviceRegistry registry;
    private final GatewayMetrics metrics;
    private final HttpServer server;
    private final TelemetryStore store;
    private final BooleanSupplier brokerConnected;
    private final JsonFactory json = new JsonFactory();

    public HealthApi(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics)
            throws IOException {
        this(config, registry, metrics, new io.fleet.gateway.store.NoOpTelemetryStore());
    }

    public HealthApi(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics,
            TelemetryStore store) throws IOException {
        // Fail closed. With no ingestor to ask, this API cannot see a broker
        // connection, and reporting one it cannot see is the dangerous
        // direction: a caller that forgets the supplier would get a /ready
        // that can never return 503, silently reinstating the defect the
        // endpoint exists to fix, with every probe still answering 200.
        this(config, registry, metrics, store, () -> false);
    }

    public HealthApi(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics,
            TelemetryStore store, BooleanSupplier brokerConnected) throws IOException {
        this.registry = registry;
        this.metrics = metrics;
        this.store = store;
        this.brokerConnected = brokerConnected;
        this.server = HttpServer.create(
                new InetSocketAddress(config.httpHost(), config.httpPort()), 0);
        this.server.createContext("/health", guarded(this::handleHealth));
        this.server.createContext("/ready", guarded(this::handleReady));
        this.server.createContext("/devices", guarded(this::handleDevices));
        this.server.createContext("/history", guarded(this::handleHistory));
        this.server.createContext("/stats", guarded(this::handleStats));
        this.server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "gateway-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    /** The bound port, which differs from the configured one when that was 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        byte[] body = write(generator -> {
            generator.writeStartObject();
            generator.writeStringField("status", "UP");
            // Liveness, not readiness: the API answers whether or not the
            // broker is reachable, because a gateway that has lost the broker
            // is exactly what an operator needs to be able to query.
            generator.writeBooleanField("brokerConnected", brokerConnected.getAsBoolean());
            generator.writeNumberField("devicesKnown", registry.size());
            generator.writeNumberField("devicesReporting", registry.reportingCount());
            generator.writeNumberField("devicesOnline", registry.onlineCount());
            // The gateway's own judgement, as opposed to what the broker and
            // the devices report. This is what recovery acts on.
            generator.writeObjectFieldStart("health");
            for (var entry : registry.healthCounts().entrySet()) {
                generator.writeNumberField(entry.getKey().name(), entry.getValue());
            }
            generator.writeEndObject();
            generator.writeNumberField("heartbeatsAccepted", metrics.heartbeatsAcceptedCount());
            generator.writeNumberField("heartbeatsMalformed", metrics.heartbeatsMalformedCount());
            generator.writeNumberField("failuresDetected", metrics.failuresDetectedCount());
            generator.writeNumberField("recoveriesObserved", metrics.recoveriesObservedCount());
            generator.writeNumberField("meanRecoveryMillis", metrics.meanRecoveryMillis());
            generator.writeNumberField("monitorErrors", metrics.monitorErrorCount());
            generator.writeNumberField("eventPublishFailures", metrics.eventPublishFailureCount());
            generator.writeNumberField("telemetryAccepted", metrics.acceptedCount());
            generator.writeNumberField("telemetryMalformed", metrics.malformedCount());
            generator.writeNumberField("telemetryInvalid", metrics.invalidCount());
            generator.writeNumberField("presenceEvents", metrics.presenceCount());
            generator.writeNumberField("unroutableMessages", metrics.unroutableCount());
            generator.writeNumberField("invalidPresence", metrics.invalidPresenceCount());
            generator.writeNumberField("handlerErrors", metrics.handlerErrorCount());
            generator.writeNumberField("connectionLosses", metrics.connectionLossCount());
            generator.writeEndObject();
        });
        respond(exchange, 200, body);
    }

    /**
     * Readiness: is this gateway ingesting, not merely running?
     *
     * <p>Separate from {@code /health} because they answer different
     * questions and Kubernetes asks both. {@code /health} is unconditionally
     * 200 while the process lives — as a readiness probe it could never fail,
     * so a Service would keep routing to a gateway that had lost the broker
     * and was recording nothing.
     *
     * <p>Deliberately not a liveness probe. A broker outage would restart
     * every gateway replica in a loop, when the correct behaviour is to stay
     * up, keep serving history, and reconnect.
     */
    private void handleReady(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        boolean connected = brokerConnected.getAsBoolean();
        byte[] body = write(generator -> {
            generator.writeStartObject();
            generator.writeStringField("status", connected ? "READY" : "NOT_READY");
            generator.writeBooleanField("brokerConnected", connected);
            generator.writeEndObject();
        });
        respond(exchange, connected ? 200 : 503, body);
    }

    private void handleDevices(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/devices".length());

        // HttpServer matches contexts by prefix, so /devicesfoo also lands
        // here. Without requiring the separator, stripping a fixed character
        // would turn that into a lookup for "oo" and answer 200 with a real
        // device rather than 404.
        if (!suffix.isEmpty() && !suffix.startsWith("/")) {
            respond(exchange, 404, write(generator -> {
                generator.writeStartObject();
                generator.writeStringField("error", "not found");
                generator.writeStringField("path", path);
                generator.writeEndObject();
            }));
            return;
        }

        if (suffix.isEmpty() || suffix.equals("/")) {
            List<DeviceRecord> all = registry.all();
            respond(exchange, 200, write(generator -> {
                generator.writeStartObject();
                generator.writeNumberField("count", all.size());
                generator.writeArrayFieldStart("devices");
                for (DeviceRecord record : all) {
                    writeDevice(generator, record);
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }));
            return;
        }

        String deviceId = suffix.substring(1);
        Optional<DeviceRecord> found = registry.find(deviceId);
        if (found.isEmpty()) {
            respond(exchange, 404, write(generator -> {
                generator.writeStartObject();
                generator.writeStringField("error", "unknown device");
                generator.writeStringField("deviceId", deviceId);
                generator.writeEndObject();
            }));
            return;
        }
        respond(exchange, 200, write(generator -> writeDevice(generator, found.get())));
    }

    /**
     * {@code GET /history?device=<id>&from=<ms>&to=<ms>} — stored readings.
     *
     * <p>Reads from the store rather than the registry: the registry keeps
     * only the latest reading per device, because holding a fleet's history in
     * memory is what the store exists to avoid.
     */
    private void handleHistory(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String deviceId = query.get("device");
        if (deviceId == null || deviceId.isBlank()) {
            respond(exchange, 400, write(generator -> {
                generator.writeStartObject();
                generator.writeStringField("error", "device parameter is required");
                generator.writeEndObject();
            }));
            return;
        }
        long to = parseLongOr(query.get("to"), System.currentTimeMillis());
        long from = parseLongOr(query.get("from"), 0L);

        int limit = (int) Math.min(MAX_HISTORY_ROWS,
                Math.max(1L, parseLongOr(query.get("limit"), MAX_HISTORY_ROWS)));

        try {
            List<Telemetry> all = store.history(deviceId, from, to);
            boolean truncated = all.size() > limit;
            List<Telemetry> readings = truncated ? all.subList(0, limit) : all;
            var integrity = store.integrity(from, to);

            respond(exchange, 200, write(generator -> {
                generator.writeStartObject();
                generator.writeStringField("deviceId", deviceId);
                generator.writeNumberField("from", from);
                generator.writeNumberField("to", to);
                generator.writeNumberField("count", readings.size());
                generator.writeBooleanField("truncated", truncated);
                writeIntegrity(generator, integrity);
                generator.writeArrayFieldStart("readings");
                for (Telemetry reading : readings) {
                    generator.writeStartObject();
                    generator.writeNumberField("ts", reading.timestamp());
                    generator.writeNumberField("temp", reading.temperature());
                    generator.writeNumberField("vib", reading.vibration());
                    generator.writeNumberField("batt", reading.batteryLevel());
                    generator.writeStringField("status", reading.status().name());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }));
        } catch (StoreException e) {
            respondStoreFailure(exchange, e);
        }
    }

    /** {@code GET /stats?from=<ms>&to=<ms>} — fleet aggregates over a window. */
    private void handleStats(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long to = parseLongOr(query.get("to"), System.currentTimeMillis());
        long from = parseLongOr(query.get("from"), to - DEFAULT_WINDOW_MILLIS);

        try {
            var averageTemperature = store.fleetAverageTemperature(from, to);
            double rate = store.telemetryRate(from, to);
            List<String> failed = store.currentlyFailedDevices();
            var meanRecovery = store.meanRecoveryMillis(from, to);
            int recoveries = store.recoveries(from, to).size();
            var integrity = store.integrity(from, to);

            respond(exchange, 200, write(generator -> {
                generator.writeStartObject();
                generator.writeNumberField("from", from);
                generator.writeNumberField("to", to);
                writeIntegrity(generator, integrity);
                if (averageTemperature.isPresent()) {
                    generator.writeNumberField("fleetAverageTemperature",
                            averageTemperature.getAsDouble());
                } else {
                    generator.writeNullField("fleetAverageTemperature");
                }
                generator.writeNumberField("telemetryPerSecond", rate);
                generator.writeNumberField("recoveries", recoveries);
                if (meanRecovery.isPresent()) {
                    generator.writeNumberField("meanRecoveryMillis", meanRecovery.getAsDouble());
                } else {
                    generator.writeNullField("meanRecoveryMillis");
                }
                generator.writeArrayFieldStart("currentlyFailed");
                for (String deviceId : failed) {
                    generator.writeString(deviceId);
                }
                generator.writeEndArray();
                generator.writeEndObject();
            }));
        } catch (StoreException e) {
            respondStoreFailure(exchange, e);
        }
    }

    /**
     * Writes the window's integrity alongside the figures it qualifies.
     *
     * <p>The gateway keeps running when the store fails, so a window can have
     * holes while every number over it looks entirely normal. Emitting the
     * gap count next to the numbers means a consumer — a chart, a report, a
     * person — cannot read past it by accident, and gives the reproducibility
     * contract something concrete to check.
     */
    private static void writeIntegrity(JsonGenerator generator, StoreIntegrity integrity)
            throws IOException {
        generator.writeObjectFieldStart("integrity");
        generator.writeBooleanField("complete", integrity.isComplete());
        generator.writeNumberField("droppedWrites", integrity.droppedWrites());
        generator.writeNumberField("dropEvents", integrity.dropEvents());
        generator.writeNumberField("lastDropAtMillis", integrity.lastDropAtMillis());
        generator.writeStringField("summary", integrity.describe());
        generator.writeEndObject();
    }

    /**
     * A store failure is reported as 503, not 500.
     *
     * <p>The gateway is still detecting failures; only the history is
     * unavailable. Reporting it as a server error would suggest the whole
     * component is down when the part that matters most is not.
     */
    private void respondStoreFailure(HttpExchange exchange, StoreException e) throws IOException {
        System.err.println("store query failed: " + e.getMessage());
        respond(exchange, 503, write(generator -> {
            generator.writeStartObject();
            generator.writeStringField("error", "telemetry store unavailable");
            generator.writeStringField("detail", String.valueOf(e.getMessage()));
            generator.writeEndObject();
        }));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return parsed;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parsed.put(
                        URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parsed;
    }

    /** Falls back rather than failing: a malformed bound is not worth a 400. */
    private static long parseLongOr(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void writeDevice(JsonGenerator generator, DeviceRecord record) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("deviceId", record.deviceId());
        generator.writeStringField("health", record.health().name());
        generator.writeNumberField("healthChangedAtMillis", record.healthChangedAtMillis());
        generator.writeNumberField("offlineSinceMillis", record.offlineSinceMillis());
        generator.writeNumberField("lastHeartbeatAtMillis", record.lastHeartbeatAtMillis());
        if (record.presence() == null) {
            generator.writeNullField("presence");
        } else {
            generator.writeStringField("presence", record.presence().name());
        }
        generator.writeNumberField("presenceAtMillis", record.presenceAtMillis());
        generator.writeNumberField("lastTelemetryAtMillis", record.lastTelemetryAtMillis());
        generator.writeNumberField("telemetryAccepted", record.telemetryAccepted());
        generator.writeNumberField("telemetryRejected", record.telemetryRejected());
        // Surfaced explicitly: retained presence outlives the device that set
        // it, so a consumer must be able to tell a live device from the ghost
        // of one that existed on an earlier run.
        generator.writeBooleanField("presenceOnly", record.presenceOnly());

        Telemetry last = record.lastTelemetry();
        if (last == null) {
            generator.writeNullField("lastTelemetry");
        } else {
            generator.writeObjectFieldStart("lastTelemetry");
            generator.writeNumberField("ts", last.timestamp());
            generator.writeNumberField("temp", last.temperature());
            generator.writeNumberField("vib", last.vibration());
            generator.writeNumberField("batt", last.batteryLevel());
            generator.writeNumberField("lat", last.latitude());
            generator.writeNumberField("lon", last.longitude());
            generator.writeStringField("status", last.status().name());
            generator.writeEndObject();
        }
        generator.writeEndObject();
    }

    /**
     * Turns any failure inside a handler into a 500 rather than a dropped
     * connection. HttpServer closes the exchange without a status line when a
     * handler throws, and a health endpoint that fails by hanging up cannot be
     * told apart from a network fault by whatever is monitoring it.
     */
    private HttpHandler guarded(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (RuntimeException | IOException e) {
                System.err.println("error serving " + exchange.getRequestURI() + ": " + e);
                try {
                    respond(exchange, 500, "{\"error\":\"internal error\"}"
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The client is already gone; nothing further to report.
                }
            } finally {
                exchange.close();
            }
        };
    }

    private boolean rejectNonGet(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            return false;
        }
        respond(exchange, 405, "{\"error\":\"method not allowed\"}"
                .getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private byte[] write(JsonBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(out)) {
            body.writeTo(generator);
        }
        return out.toByteArray();
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(STOP_DELAY_SECONDS);
    }

    @FunctionalInterface
    private interface JsonBody {
        void writeTo(JsonGenerator generator) throws IOException;
    }
}
