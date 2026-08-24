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
 * @param pod             the pod this recovery concerns. Named neutrally
 *                        because it is not always a replacement: for
 *                        {@code NOT_NEEDED} it is the live pod that made a
 *                        replacement unnecessary, and calling that field
 *                        "replacementPod" would have anyone counting
 *                        replacements count it as one
 * @param detectedAtMillis when the gateway declared the failure
 * @param actedAtMillis   when the operator finished acting
 * @param outcome         what actually happened
 */
public record Recovery(
        String deviceId,
        String recoveryId,
        String pod,
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

    /**
     * Whether {@link #durationMillis()} is a measurement rather than just
     * arithmetic.
     *
     * <p>False unless something was actually replaced — for any other outcome
     * the subtraction spans a failure this recovery did not answer. False too
     * when it comes out negative, which it can: the two ends are read from two
     * different pods' clocks, and a gateway running slightly ahead of the
     * operator turns a 160 ms recovery into a negative one.
     */
    public boolean hasMeaningfulDuration() {
        return outcome == RecoveryOutcome.REPLACED && durationMillis() >= 0L;
    }
}
