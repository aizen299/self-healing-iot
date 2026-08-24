package io.fleet.recovery.k8s;

/** A Kubernetes API call that did not do what was asked. */
public class KubernetesException extends Exception {

    private static final long serialVersionUID = 1L;

    public KubernetesException(String message) {
        super(message);
    }

    public KubernetesException(String message, Throwable cause) {
        super(message, cause);
    }
}
