package io.fleet.recovery;

/**
 * One recovery, start to finish.
 *
 * <p>The explicit state the design calls for: which device, which recovery,
 * which replacement, and how long it took. Held so the operator can answer
 * "what did you do about that failure" rather than only "I did something".
 *
 * @param deviceId        device that failed
 * @param recoveryId      deterministic id derived from the failure
 * @param replacementPod  pod created, or the one found already present
 * @param detectedAtMillis when the gateway declared the failure
 * @param actedAtMillis   when the operator finished acting
 * @param outcome         what actually happened
 */
public record Recovery(
        String deviceId,
        String recoveryId,
        String replacementPod,
        long detectedAtMillis,
        long actedAtMillis,
        RecoveryOutcome outcome) {

    /**
     * Detection to replacement, in milliseconds.
     *
     * <p>The operator's half of MTTR, and only its half: it ends when the
     * replacement pod is accepted by the API server, not when the device is
     * publishing again. The gateway measures the other half — it watches the
     * replacement come back and reports {@code recoveryDurationMillis} from
     * failure to confirmed heartbeats. Reporting this number as MTTR would
     * understate it by everything a JVM takes to start.
     */
    public long durationMillis() {
        return actedAtMillis - detectedAtMillis;
    }
}
