package io.fleet.recovery.k8s;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The Kubernetes API over plain HTTPS, using the pod's own service account.
 *
 * <p>No client library. This makes four calls against a documented REST API
 * and runs inside the cluster, where the credentials are three files the
 * kubelet has already mounted — see ADR-011 for why that beat adding a
 * dependency tree to this project.
 *
 * <p>The token is re-read on every request rather than cached. Kubernetes
 * projects bound service account tokens with an expiry and rotates the file
 * in place; a token read once at startup stops working roughly an hour in,
 * and the failure would look like the operator mysteriously losing
 * permissions long after it had been deployed successfully.
 */
public final class HttpKubernetesApi implements KubernetesApi {

    private static final Path TOKEN_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path CA_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

    private final HttpClient http;
    private final String baseUrl;
    private final String namespace;
    private final Path tokenPath;
    private final Duration timeout;
    private final JsonFactory json = new JsonFactory();

    public static HttpKubernetesApi inCluster(String namespace, Duration timeout)
            throws KubernetesException {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || port == null) {
            throw new KubernetesException(
                    "KUBERNETES_SERVICE_HOST/PORT are not set; this operator is meant to run"
                            + " inside the cluster it manages");
        }
        // IPv6 addresses arrive bare and have to be bracketed for a URL.
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return new HttpKubernetesApi("https://" + authority + ":" + port, namespace,
                TOKEN_PATH, trustFrom(CA_PATH), timeout);
    }

    HttpKubernetesApi(String baseUrl, String namespace, Path tokenPath,
            SSLContext ssl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.namespace = namespace;
        this.tokenPath = tokenPath;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .sslContext(ssl)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Trusts the cluster CA, and only the cluster CA.
     *
     * <p>The alternative — an all-trusting trust manager — is the standard way
     * this gets written and is wrong: the operator holds a credential that can
     * delete pods, and would hand it to anything that answered on the service
     * address.
     */
    private static SSLContext trustFrom(Path caPath) throws KubernetesException {
        try (InputStream in = Files.newInputStream(caPath)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certs =
                    factory.generateCertificates(in);

            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int index = 0;
            for (java.security.cert.Certificate certificate : certs) {
                trust.setCertificateEntry("k8s-ca-" + index++, (X509Certificate) certificate);
            }

            TrustManagerFactory managers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new KubernetesException("could not build a trust store from " + caPath, e);
        }
    }

    @Override
    public List<PodRef> listPods(String labelKey, String labelValue) throws KubernetesException {
        String selector = URLEncoder.encode(labelKey + "=" + labelValue, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(request(podsUrl() + "?labelSelector=" + selector)
                .GET().build(), "list pods " + labelKey + "=" + labelValue);
        if (response.statusCode() != 200) {
            throw new KubernetesException("listing pods returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return parsePodList(response.body());
    }

    @Override
    public boolean createPod(String manifestJson) throws KubernetesException {
        HttpResponse<String> response = send(request(podsUrl())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(manifestJson, StandardCharsets.UTF_8))
                .build(), "create pod");
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            return true;
        }
        // 409 *AlreadyExists* is the idempotency guarantee, not a failure: the
        // replacement's name is derived from the recovery id, so a duplicate
        // failure event asks the API server to create a pod that is already
        // there and the API server refuses. That refusal is what makes "the
        // same failure event twice must not create two replacements" true
        // across operator restarts, where an in-memory ledger would have
        // forgotten.
        //
        // The reason is checked rather than inferred from the status. A 409
        // that means something else — and Conflict covers more than one thing
        // — would otherwise be reported as a successful no-op, the device
        // would be recorded as handled, every redelivery would short-circuit,
        // and the fleet would stay one device down while the topic said it had
        // recovered. The guarantee this whole design rests on should assert
        // what it relies on.
        if (response.statusCode() == 409) {
            if (isAlreadyExists(response.body())) {
                return false;
            }
            throw new KubernetesException("creating a pod was refused with a conflict that is"
                    + " not AlreadyExists: " + response.body());
        }
        throw new KubernetesException("creating a pod returned HTTP " + response.statusCode()
                + ": " + response.body());
    }

    @Override
    public void deletePod(String name, boolean force) throws KubernetesException {
        HttpRequest.Builder builder = request(podsUrl() + "/" + encode(name));
        if (force) {
            // gracePeriodSeconds=0 severs the connection instead of letting the
            // device publish a retained SHUTDOWN and disconnect cleanly, so the
            // broker fires its Last Will and the gateway reads a failure rather
            // than a deliberate stop (ADR-006).
            builder = builder.header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                            "{\"apiVersion\":\"v1\",\"kind\":\"DeleteOptions\","
                                    + "\"gracePeriodSeconds\":0}", StandardCharsets.UTF_8));
        } else {
            builder = builder.DELETE();
        }
        HttpResponse<String> response = send(builder.build(), "delete pod " + name);
        // 404 means someone else already removed it, which is the state this
        // call was asking for.
        if (response.statusCode() != 200 && response.statusCode() != 202
                && response.statusCode() != 404) {
            throw new KubernetesException("deleting pod " + name + " returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
    }

    /**
     * Whether a Status body names {@code AlreadyExists} as its reason.
     *
     * <p>Read from the field rather than matched in the raw text: a pod whose
     * name or labels happened to contain the word would otherwise decide this.
     */
    private boolean isAlreadyExists(String body) {
        try (JsonParser parser = json.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("reason".equals(field)) {
                    return "AlreadyExists".equals(parser.getText());
                }
                parser.skipChildren();
            }
        } catch (IOException e) {
            // A 409 whose body will not parse is not something to treat as a
            // successful no-op.
            return false;
        }
        return false;
    }

    private String podsUrl() {
        return baseUrl + "/api/v1/namespaces/" + encode(namespace) + "/pods";
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder request(String url) throws KubernetesException {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json");
    }

    private String token() throws KubernetesException {
        try {
            return Files.readString(tokenPath).trim();
        } catch (IOException e) {
            throw new KubernetesException("could not read the service account token at "
                    + tokenPath, e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String what)
            throws KubernetesException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KubernetesException("could not " + what + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KubernetesException("interrupted while trying to " + what, e);
        }
    }

    /**
     * Pulls name, phase, and labels out of a PodList.
     *
     * <p>Streaming rather than a tree: a fleet's PodList carries the full spec
     * of every pod, and this needs three fields from each.
     */
    private List<PodRef> parsePodList(String body) throws KubernetesException {
        List<PodRef> pods = new ArrayList<>();
        try (JsonParser parser = json.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new KubernetesException("pod list is not a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if ("items".equals(parser.currentName())) {
                    parser.nextToken();
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        pods.add(parsePod(parser, json));
                    }
                } else {
                    parser.nextToken();
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new KubernetesException("could not read the pod list: " + e.getMessage(), e);
        }
        return pods;
    }

    /**
     * Reads one pod, keeping both the three fields the operator decides on and
     * the manifest it will clone.
     *
     * <p>The manifest is copied out here because this is the only place it
     * passes through: re-serialising as we go costs one buffer per pod and
     * saves a second API call per recovery.
     */
    private static PodRef parsePod(JsonParser parser, JsonFactory json) throws IOException {
        String name = null;
        String phase = "Unknown";
        Map<String, String> labels = new LinkedHashMap<>();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(4096);
        JsonGenerator manifest = json.createGenerator(raw);
        manifest.writeStartObject();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String section = parser.currentName();
            parser.nextToken();
            // Buffered whole, then re-read for the three fields. Copying the
            // structure is what lets the replacement be cloned without a
            // second GET, and re-reading a few hundred bytes twice is cheaper
            // than a round trip to the API server.
            manifest.writeFieldName(section);
            manifest.copyCurrentStructure(parser);
        }
        manifest.writeEndObject();
        manifest.close();

        String body = raw.toString(StandardCharsets.UTF_8);
        try (JsonParser fields = json.createParser(body)) {
            fields.nextToken();
            while (fields.nextToken() != JsonToken.END_OBJECT) {
                String section = fields.currentName();
                fields.nextToken();
                switch (section) {
                    case "metadata" -> {
                        while (fields.nextToken() != JsonToken.END_OBJECT) {
                            String field = fields.currentName();
                            fields.nextToken();
                            if ("name".equals(field)) {
                                name = fields.getText();
                            } else if ("labels".equals(field)) {
                                while (fields.nextToken() != JsonToken.END_OBJECT) {
                                    String key = fields.currentName();
                                    fields.nextToken();
                                    labels.put(key, fields.getText());
                                }
                            } else {
                                fields.skipChildren();
                            }
                        }
                    }
                    case "status" -> {
                        while (fields.nextToken() != JsonToken.END_OBJECT) {
                            String field = fields.currentName();
                            fields.nextToken();
                            if ("phase".equals(field)) {
                                phase = fields.getText();
                            } else {
                                fields.skipChildren();
                            }
                        }
                    }
                    default -> fields.skipChildren();
                }
            }
        }
        return new PodRef(name, phase, labels, body);
    }
}
