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

    private final Map<String, PodRef> pods = new LinkedHashMap<>();

    /** Pods this fake actually created — not calls, so a duplicate is absent. */
    final List<String> created = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();

    /** Set to make the next call of any kind fail, as an unreachable cluster would. */
    KubernetesException nextFailure;

    /** Set to make only createPod fail, as a quota or admission webhook would. */
    KubernetesException failOnCreate;

    void addPod(String name, String phase, Map<String, String> labels, String manifest) {
        pods.put(name, new PodRef(name, phase, labels, manifest));
    }

    @Override
    public List<PodRef> listPods(String labelKey, String labelValue) throws KubernetesException {
        failIfAsked();
        return pods.values().stream()
                .filter(pod -> labelValue.equals(pod.labels().get(labelKey)))
                .toList();
    }

    @Override
    public boolean createPod(String manifestJson) throws KubernetesException {
        failIfAsked();
        if (failOnCreate != null) {
            KubernetesException failure = failOnCreate;
            failOnCreate = null;
            throw failure;
        }
        String name = nameOf(manifestJson);
        if (pods.containsKey(name)) {
            // What a real API server does: 409 AlreadyExists. This is the whole
            // idempotency mechanism, so the fake has to honour it — and the
            // refused call must not land in `created`, or a test asserting one
            // creation would pass on the duplicate path for the wrong reason.
            return false;
        }
        created.add(name);
        pods.put(name, new PodRef(name, "Pending", labelsOf(manifestJson), manifestJson));
        return true;
    }

    @Override
    public void deletePod(String name, boolean force) throws KubernetesException {
        failIfAsked();
        deleted.add(name);
        pods.remove(name);
    }

    PodRef pod(String name) {
        return pods.get(name);
    }

    /** The manifest a pod was created with, for tests that inspect it. */
    String manifestOf(String name) {
        PodRef pod = pods.get(name);
        return pod == null ? null : pod.manifest();
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
