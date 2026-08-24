package io.fleet.recovery.k8s;

import java.util.Map;

/**
 * A pod, as much of it as this operator needs.
 *
 * @param name     metadata.name
 * @param phase    status.phase — Pending, Running, Succeeded, Failed, Unknown
 * @param labels   metadata.labels, which is how a device is identified
 * @param manifest the pod's full JSON, kept because a replacement is cloned
 *                 from it. The list response already contained it, so holding
 *                 it here is what removes a second API call per recovery from
 *                 the path whose latency Pillar B measures
 */
public record PodRef(String name, String phase, Map<String, String> labels, String manifest) {

    /**
     * Whether this pod still counts as the fleet's copy of its device.
     *
     * <p>Succeeded and Failed pods have stopped for good — {@code
     * restartPolicy: Never} (ADR-010) means nothing will start them again —
     * so they do not count as a device being present, though they do still
     * hold their name, which is why recovery deletes them after replacing
     * them.
     *
     * <p><b>Unknown does not count as alive.</b> A pod goes Unknown when its
     * kubelet stops reporting: a partitioned or dead node, which is one of
     * the failure modes automated recovery exists for. Treating it as present
     * would have the operator decline to replace a device precisely when the
     * node holding it has gone, and every redelivery would reach the same
     * conclusion, leaving the fleet permanently short while the operator
     * reported it had nothing to do.
     */
    public boolean isAlive() {
        return "Pending".equals(phase) || "Running".equals(phase);
    }

    public String label(String key) {
        return labels.get(key);
    }
}
