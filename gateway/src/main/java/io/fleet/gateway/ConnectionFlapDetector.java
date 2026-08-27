package io.fleet.gateway;

import java.time.Clock;
import java.util.Optional;

/**
 * Notices when the broker connection is dropping repeatedly and says why.
 *
 * <p>A single lost connection is ordinary: brokers restart. A run of them in
 * under a minute is almost always one specific mistake, and the log does not
 * say so on its own — Paho reports every one of them as
 * {@code Connection lost (32109) - java.io.EOFException}, which describes what
 * the socket did and nothing about the cause.
 *
 * <p>The cause is nearly always a second gateway. MQTT client ids are unique
 * per broker by definition: when a client connects with an id that already has
 * a session, the broker disconnects the older one. Two gateways sharing
 * {@code GATEWAY_CLIENT_ID} therefore evict each other in a loop, and the
 * consequence is worse than an outage — a disconnected gateway is not
 * receiving heartbeats, so it declares healthy devices failed and the recovery
 * operator is asked to replace pods that are running perfectly well.
 *
 * <p>That is not hypothetical; it is what a `kubectl scale deployment/gateway
 * --replicas=3` did to this fleet, and working out why took considerably
 * longer than reading one sentence would have.
 *
 * <p>The gateway is a singleton by construction — it holds the device registry
 * in memory and an exclusive lock on the embedded store — so the fix is always
 * to run one, never to make the id unique.
 */
final class ConnectionFlapDetector {

    /** Long enough to span a broker restart's reconnect, short enough to mean something. */
    static final long WINDOW_MILLIS = 60_000L;

    /** One loss is noise, two is bad luck, three in a minute is a pattern. */
    static final int THRESHOLD = 3;

    private final Clock clock;

    private long windowStartMillis;
    private int lossesInWindow;
    private boolean reported;

    ConnectionFlapDetector(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a lost connection.
     *
     * @return the explanation to log, once per window, or empty while the
     *         losses are still within what a restarting broker would explain
     */
    synchronized Optional<String> recordLoss(String clientId) {
        long now = clock.millis();
        if (lossesInWindow == 0 || now - windowStartMillis > WINDOW_MILLIS) {
            windowStartMillis = now;
            lossesInWindow = 0;
            reported = false;
        }
        lossesInWindow++;

        if (lossesInWindow < THRESHOLD || reported) {
            return Optional.empty();
        }
        reported = true;
        return Optional.of(
                "gateway has lost its broker connection " + lossesInWindow
                        + " times in under a minute. The usual cause is a second gateway"
                        + " using the same client id '" + clientId + "': a broker"
                        + " disconnects the older session when a client reconnects with"
                        + " an id already in use, so two gateways evict each other in a"
                        + " loop. The gateway is a singleton — check that nothing has"
                        + " scaled it (kubectl -n fleet get deploy gateway). While"
                        + " disconnected it misses heartbeats and will declare healthy"
                        + " devices failed.");
    }
}
