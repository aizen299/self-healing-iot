package io.fleet.recovery;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The identity of one recovery attempt.
 *
 * <p>Derived from the failure it answers — the device and the instant the
 * gateway declared it offline — and from nothing else. That is what makes
 * recovery idempotent: the same failure event, redelivered after a rebalance
 * or replayed after an operator restart, produces the same id, therefore the
 * same replacement pod name, and the API server refuses the second create.
 *
 * <p>Correctness does not rest on the operator remembering anything. An
 * in-memory ledger forgets when the process dies, and Kafka's at-least-once
 * delivery guarantees the redelivery will outlive it.
 *
 * <p>Deliberately not a random UUID or a timestamp: either would make the
 * second delivery of one event look like a second failure, which is exactly
 * the duplicate-replacement bug the requirement names.
 */
public final class RecoveryId {

    /**
     * Enough hex to make a collision between two recoveries of the same device
     * implausible, short enough to leave room in a 63-character label value
     * and a 253-character pod name.
     */
    private static final int LENGTH = 10;

    private RecoveryId() {
    }

    public static String of(String deviceId, long detectedAtMillis) {
        byte[] seed = (deviceId + "@" + detectedAtMillis).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM by the platform specification.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The pod name a recovery must use.
     *
     * <p>Deterministic for the same reason the id is: the name is the
     * idempotency key the API server enforces. Lowercase alphanumeric and
     * dashes only, because that is what a pod name may contain.
     */
    public static String replacementPodName(String deviceId, String recoveryId) {
        return "edge-device-" + deviceId.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase()
                + "-r-" + recoveryId;
    }
}
