package io.fleet.recovery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fleet.common.PrometheusText;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * One route, so Prometheus has something to scrape.
 *
 * <p>The operator had no HTTP surface before this, and it gains exactly the
 * one it needs. In particular it gains no probes: ADR-010's argument still
 * holds — a liveness probe that restarted the operator during a Kafka outage
 * would take the recovery path down for the duration, and the Deployment
 * already restarts a process that exits. A scrape endpoint is not a probe, and
 * this one deliberately answers 200 whether or not Kafka is reachable, because
 * an operator that cannot consume is exactly what a dashboard needs to show.
 *
 * <p>A single daemon thread. Scrapes arrive every 15 s and take microseconds;
 * a pool would be more machinery than the load justifies, and a non-daemon
 * thread would keep the JVM alive after the poll loop had stopped.
 */
public final class MetricsServer implements AutoCloseable {

    private static final int STOP_DELAY_SECONDS = 1;

    private final HttpServer server;

    public MetricsServer(int port, Supplier<String> body) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.server.createContext("/metrics", exchange -> handle(exchange, body));
        this.server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "operator-metrics");
            thread.setDaemon(true);
            return thread;
        }));
    }

    private static void handle(HttpExchange exchange, Supplier<String> body) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", PrometheusText.contentType());
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        } catch (RuntimeException e) {
            // A scrape that throws must not kill the handler thread and leave
            // every later scrape hanging — the operator would keep recovering
            // devices while the dashboard showed it as gone.
            System.err.println("failed to serve a scrape: " + e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    public void start() {
        server.start();
    }

    /** The bound port, which is not the configured one when that was 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(STOP_DELAY_SECONDS);
    }
}
