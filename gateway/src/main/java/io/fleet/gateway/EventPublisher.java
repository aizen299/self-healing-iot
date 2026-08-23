package io.fleet.gateway;

/**
 * Announces health transitions to the rest of the system.
 *
 * <p>An interface so the detector does not depend on MQTT. The monitor's job
 * is deciding that a device has failed; how that decision travels is a
 * separate concern, and Phase 6 will add a Kafka route alongside the MQTT one
 * without touching the detection logic.
 */
public interface EventPublisher {

    /**
     * Publishes a transition.
     *
     * <p>Implementations must not throw: a failure to announce must not stop
     * the sweep that produced it, or one unreachable consumer would halt
     * detection for the whole fleet. Report and count instead.
     */
    void publish(HealthTransition transition);
}
