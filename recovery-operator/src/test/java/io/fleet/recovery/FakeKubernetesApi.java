package io.fleet.recovery;

import io.fleet.recovery.k8s.KubernetesApi;
import io.fleet.recovery.k8s.KubernetesException;
import io.fleet.recovery.k8s.PodRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cluster that remembers what was asked of it.
 *
 * <p>Enforces the one API-server behaviour the idempotency guarantee actually
 * rests on: a pod name is unique, and creating one that already exists is
 * refused. A fake that quietly accepted duplicate names would let the
 * duplicate-replacement bug pass its own test.
 */
final class FakeKubernetesApi implements KubernetesApi {

    private final Map<String, String> manifests = new LinkedHashMap<>();
    private final Map<String, PodRef> pods = new LinkedHashMap<>();

    final List<String> created = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();

    /** Set to make the next call of any kind fail, as an unreachable cluster would. */
    KubernetesException nextFailure;

    void addPod(String name, String phase, Map<String, String> labels, String manifest) {
        pods.put(name, new PodRef(name, phase, labels));
        manifests.put(name, manifest);
    }

    @Override
    public List<PodRef> listPods(String labelKey, String labelValue) throws KubernetesException {
        failIfAsked();
        return pods.values().stream()
                .filter(pod -> labelValue.equals(pod.labels().get(labelKey)))
                .toList();
    }

    @Override
    public String readPod(String name) throws KubernetesException {
        failIfAsked();
        return manifests.get(name);
    }

    @Override
    public boolean createPod(String manifestJson) throws KubernetesException {
        failIfAsked();
        String name = nameOf(manifestJson);
        created.add(name);
        if (pods.containsKey(name)) {
            // What a real API server does: 409 Conflict. This is the whole
            // idempotency mechanism, so the fake has to honour it.
            return false;
        }
        pods.put(name, new PodRef(name, "Pending", labelsOf(manifestJson)));
        manifests.put(name, manifestJson);
        return true;
    }

    @Override
    public void deletePod(String name, boolean force) throws KubernetesException {
        failIfAsked();
        deleted.add(name);
        pods.remove(name);
        manifests.remove(name);
    }

    PodRef pod(String name) {
        return pods.get(name);
    }

    int podCount() {
        return pods.size();
    }

    List<String> podNames() {
        return List.copyOf(pods.keySet());
    }

    private void failIfAsked() throws KubernetesException {
        if (nextFailure != null) {
            KubernetesException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
    }

    /**
     * Enough JSON reading for a test double. Deliberately crude — the real
     * parsing is {@code ReplacementFactory}'s job and is tested there.
     */
    private static String nameOf(String manifest) {
        return between(manifest, "\"name\":\"", "\"");
    }

    private static Map<String, String> labelsOf(String manifest) {
        Map<String, String> labels = new LinkedHashMap<>();
        int start = manifest.indexOf("\"labels\":{");
        if (start < 0) {
            return labels;
        }
        int end = manifest.indexOf('}', start);
        String body = manifest.substring(start + "\"labels\":{".length(), end);
        for (String pair : body.split(",")) {
            String[] halves = pair.split(":", 2);
            if (halves.length == 2) {
                labels.put(unquote(halves[0]), unquote(halves[1]));
            }
        }
        return labels;
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        start += open.length();
        return text.substring(start, text.indexOf(close, start));
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }
}
