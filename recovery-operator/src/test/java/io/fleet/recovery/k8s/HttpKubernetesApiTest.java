package io.fleet.recovery.k8s;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-rolled Kubernetes client, driven against a real HTTP server.
 *
 * <p>ADR-011 justifies writing this by hand on the grounds that it is only a
 * few calls against a documented API. The same smallness is what makes it
 * cheap to test properly, and leaving it untested is what would turn that
 * justification into a liability — so the request shapes, the status-code
 * interpretations, and the streaming JSON parsing are all exercised here.
 *
 * <p>Plain HTTP on an ephemeral port: {@code HttpClient} ignores the SSL
 * context for an {@code http://} URL, so the one part this cannot cover is
 * the trust-store construction, which needs a real cluster CA.
 */
class HttpKubernetesApiTest {

    private HttpServer server;
    private HttpKubernetesApi api;
    private Path tokenFile;

    /** Set per test: what the next response should be. */
    private final AtomicReference<Response> reply = new AtomicReference<>();
    private final List<Request> requests = new ArrayList<>();

    private record Response(int status, String body) { }

    private record Request(String method, String uri, String authorization, String body) { }

    @BeforeEach
    void setUp() throws Exception {
        tokenFile = Files.createTempFile("sa-token", "");
        Files.writeString(tokenFile, "first-token\n");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        api = new HttpKubernetesApi("http://127.0.0.1:" + server.getAddress().getPort(),
                "fleet", tokenFile, SSLContext.getDefault(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        Files.deleteIfExists(tokenFile);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));

        Response response = reply.get();
        byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Test
    @DisplayName("listing hits the namespaced pods path with an encoded selector")
    void listBuildsTheRightRequest() throws Exception {
        reply.set(new Response(200, "{\"items\":[]}"));

        api.listPods("app", "edge-device");

        Request request = requests.get(0);
        assertEquals("GET", request.method());
        assertEquals("/api/v1/namespaces/fleet/pods?labelSelector=app%3Dedge-device",
                request.uri());
        assertEquals("Bearer first-token", request.authorization(),
                "the token must be trimmed; the file ends in a newline");
    }

    @Test
    @DisplayName("the token is re-read per request, because Kubernetes rotates it")
    void rereadsTheTokenEveryTime() throws Exception {
        reply.set(new Response(200, "{\"items\":[]}"));
        api.listPods("app", "edge-device");

        // A projected service account token is rotated in place. Cached once
        // at startup, the operator loses its permissions about an hour in,
        // long after a deploy that looked successful.
        Files.writeString(tokenFile, "rotated-token");
        api.listPods("app", "edge-device");

        assertEquals("Bearer first-token", requests.get(0).authorization());
        assertEquals("Bearer rotated-token", requests.get(1).authorization());
    }

    @Test
    @DisplayName("a pod list yields name, phase, labels, and the manifest to clone")
    void parsesAPodList() throws Exception {
        reply.set(new Response(200, """
                {"kind":"PodList","apiVersion":"v1","metadata":{"resourceVersion":"9"},
                 "items":[
                  {"metadata":{"name":"edge-device-001","uid":"u-1",
                    "labels":{"app":"edge-device","device-id":"device-001"}},
                   "spec":{"containers":[{"name":"device","image":"fleet/edge-device:0.1.0"}]},
                   "status":{"phase":"Running","podIP":"10.0.0.1"}},
                  {"metadata":{"name":"edge-device-002",
                    "labels":{"app":"edge-device","device-id":"device-002"}},
                   "spec":{"containers":[]},
                   "status":{"phase":"Failed"}}]}
                """));

        List<PodRef> pods = api.listPods("app", "edge-device");

        assertEquals(2, pods.size());
        assertEquals("edge-device-001", pods.get(0).name());
        assertEquals("Running", pods.get(0).phase());
        assertEquals("device-001", pods.get(0).label("device-id"));
        assertEquals("Failed", pods.get(1).phase());

        // The manifest is why there is no second GET per recovery.
        assertNotNull(pods.get(0).manifest());
        assertTrue(pods.get(0).manifest().contains("\"image\":\"fleet/edge-device:0.1.0\""),
                pods.get(0).manifest());
        assertTrue(pods.get(0).manifest().contains("\"uid\":\"u-1\""),
                "the clone step is what strips runtime fields, not this one");
    }

    @Test
    @DisplayName("a pod with no labels or no status parses rather than throwing")
    void toleratesSparsePods() throws Exception {
        reply.set(new Response(200, """
                {"items":[{"metadata":{"name":"bare"},"spec":{}}]}
                """));

        List<PodRef> pods = api.listPods("app", "edge-device");

        assertEquals(1, pods.size());
        assertEquals("bare", pods.get(0).name());
        assertEquals("Unknown", pods.get(0).phase());
        assertTrue(pods.get(0).labels().isEmpty());
    }

    @Test
    @DisplayName("a non-200 list is an error, not an empty fleet")
    void listFailuresAreNotSilentlyEmpty() {
        // Returning an empty list on a 403 would have the operator conclude
        // every device is gone and try to replace the whole fleet.
        reply.set(new Response(403, "{\"kind\":\"Status\",\"reason\":\"Forbidden\"}"));

        assertTrue(assertThrows(KubernetesException.class,
                () -> api.listPods("app", "edge-device")).getMessage().contains("403"));
    }

    @Test
    @DisplayName("201 is a creation")
    void createReportsSuccess() throws Exception {
        reply.set(new Response(201, "{}"));

        assertTrue(api.createPod("{\"kind\":\"Pod\"}"));
        assertEquals("POST", requests.get(0).method());
        assertEquals("/api/v1/namespaces/fleet/pods", requests.get(0).uri());
        assertEquals("{\"kind\":\"Pod\"}", requests.get(0).body());
    }

    @Test
    @DisplayName("409 AlreadyExists is the idempotency guarantee, not a failure")
    void createTreatsAlreadyExistsAsDone() throws Exception {
        reply.set(new Response(409, """
                {"kind":"Status","status":"Failure","reason":"AlreadyExists",
                 "message":"pods \\"device-002-r-abc\\" already exists"}
                """));

        assertTrue(!api.createPod("{\"kind\":\"Pod\"}"),
                "the second delivery of one failure must create nothing and not error");
    }

    @Test
    @DisplayName("a 409 that is not AlreadyExists is an error")
    void createDoesNotTreatEveryConflictAsSuccess() {
        // Reporting this as a successful no-op marks the device handled, so
        // every redelivery short-circuits and the fleet stays one device down
        // while device.recovery says it recovered.
        reply.set(new Response(409, """
                {"kind":"Status","status":"Failure","reason":"Conflict",
                 "message":"the object has been modified"}
                """));

        assertTrue(assertThrows(KubernetesException.class,
                () -> api.createPod("{\"kind\":\"Pod\"}"))
                .getMessage().contains("not AlreadyExists"));
    }

    @Test
    @DisplayName("a 409 whose body will not parse is an error too")
    void createRefusesAnUnreadableConflict() {
        reply.set(new Response(409, "not json"));

        assertThrows(KubernetesException.class, () -> api.createPod("{\"kind\":\"Pod\"}"));
    }

    @Test
    @DisplayName("deleting sends grace period zero when forced")
    void forcedDeleteSeversTheConnection() throws Exception {
        // A graceful stop lets the device publish a retained SHUTDOWN, which
        // the gateway reads as a deliberate stop rather than a failure
        // (ADR-006). Recovery needs the other thing.
        reply.set(new Response(200, "{}"));

        api.deletePod("edge-device-002", true);

        assertEquals("DELETE", requests.get(0).method());
        assertTrue(requests.get(0).body().contains("\"gracePeriodSeconds\":0"),
                requests.get(0).body());
    }

    @Test
    @DisplayName("deleting a pod that has already gone is not an error")
    void deleteToleratesAMissingPod() throws Exception {
        reply.set(new Response(404, "{\"kind\":\"Status\",\"reason\":\"NotFound\"}"));

        api.deletePod("edge-device-002", true);

        assertEquals(1, requests.size());
    }

    @Test
    @DisplayName("a refused delete is reported")
    void deleteFailuresAreReported() {
        reply.set(new Response(403, "{\"reason\":\"Forbidden\"}"));

        assertTrue(assertThrows(KubernetesException.class,
                () -> api.deletePod("edge-device-002", false)).getMessage().contains("403"));
    }

    @Test
    @DisplayName("a name with a slash cannot escape the pods path")
    void encodesThePodName() throws Exception {
        reply.set(new Response(404, "{}"));

        api.deletePod("../../secrets/token", true);

        assertTrue(requests.get(0).uri().startsWith("/api/v1/namespaces/fleet/pods/"),
                requests.get(0).uri());
        assertTrue(!requests.get(0).uri().contains("/secrets/"), requests.get(0).uri());
    }
}
