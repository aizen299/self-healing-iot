package io.fleet.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fleet.common.Telemetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

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
 *   <li>{@code GET /devices} — every known device</li>
 *   <li>{@code GET /devices/{id}} — one device, 404 if unknown</li>
 * </ul>
 */
public final class HealthApi implements AutoCloseable {

    private static final int STOP_DELAY_SECONDS = 1;

    private final DeviceRegistry registry;
    private final GatewayMetrics metrics;
    private final HttpServer server;
    private final JsonFactory json = new JsonFactory();

    public HealthApi(GatewayConfig config, DeviceRegistry registry, GatewayMetrics metrics)
            throws IOException {
        this.registry = registry;
        this.metrics = metrics;
        this.server = HttpServer.create(
                new InetSocketAddress(config.httpHost(), config.httpPort()), 0);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/devices", this::handleDevices);
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
            generator.writeNumberField("devicesKnown", registry.size());
            generator.writeNumberField("devicesReporting", registry.reportingCount());
            generator.writeNumberField("devicesOnline", registry.onlineCount());
            generator.writeNumberField("telemetryAccepted", metrics.acceptedCount());
            generator.writeNumberField("telemetryMalformed", metrics.malformedCount());
            generator.writeNumberField("telemetryInvalid", metrics.invalidCount());
            generator.writeNumberField("presenceEvents", metrics.presenceCount());
            generator.writeNumberField("unroutableMessages", metrics.unroutableCount());
            generator.writeNumberField("connectionLosses", metrics.connectionLossCount());
            generator.writeEndObject();
        });
        respond(exchange, 200, body);
    }

    private void handleDevices(HttpExchange exchange) throws IOException {
        if (rejectNonGet(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/devices".length());

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

    private void writeDevice(JsonGenerator generator, DeviceRecord record) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("deviceId", record.deviceId());
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
