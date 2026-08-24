package io.fleet.recovery.k8s;

import java.util.Map;

/**
 * The little this operator needs to know about a pod.
 *
 * @param name   metadata.name
 * @param phase  status.phase — Pending, Running, Succeeded, Failed, Unknown
 * @param labels metadata.labels, which is how a device is identified
 */
public record PodRef(String name, String phase, Map<String, String> labels) {

    /**
     * Whether this pod is still a candidate to be the fleet's copy of its
     * device.
     *
     * <p>Succeeded and Failed pods have stopped for good — {@code
     * restartPolicy: Never} (ADR-010) means nothing will start them again —
     * so they do not count as a device being present, but they do still hold
     * their name, which is why recovery deletes them before replacing them.
     */
    public boolean isAlive() {
        return "Pending".equals(phase) || "Running".equals(phase) || "Unknown".equals(phase);
    }

    public String label(String key) {
        return labels.get(key);
    }
}
