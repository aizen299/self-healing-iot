package io.fleet.recovery.k8s;

import java.util.List;

/**
 * The four things this operator does to a cluster.
 *
 * <p>An interface, not a class, for the reason every seam in this project is
 * one: the recovery logic is the part worth testing, and testing it through a
 * real API server would test Kubernetes. {@code FakeKubernetesApi} in the
 * tests records calls and can be made to fail on demand.
 *
 * <p>Deliberately narrow. No watches, no informers, no custom resources — the
 * trigger is a Kafka topic (ADR-011), so this never needs to observe the
 * cluster continuously, only to look and act when an event arrives.
 */
public interface KubernetesApi {

    /** Pods in the operator's namespace matching {@code key=value}. */
    List<PodRef> listPods(String labelKey, String labelValue) throws KubernetesException;

    /** One pod's full manifest as JSON, or empty when it does not exist. */
    String readPod(String name) throws KubernetesException;

    /**
     * Creates a pod from a JSON manifest.
     *
     * @return {@code true} if this call created it, {@code false} if the API
     *         server rejected it as already existing — which is not an error
     *         here but the idempotency guarantee working (ADR-011)
     */
    boolean createPod(String manifestJson) throws KubernetesException;

    /** Deletes a pod, tolerating one that has already gone. */
    void deletePod(String name, boolean force) throws KubernetesException;
}
