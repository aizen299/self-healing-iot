package io.fleet.recovery;

/** What happened to one failure event. */
public enum RecoveryOutcome {

    /** A replacement pod was created by this operator, for this event. */
    REPLACED,

    /**
     * The replacement already existed, so nothing was created.
     *
     * <p>The expected outcome for a redelivered event, and the observable
     * proof that recovery is idempotent rather than merely intended to be.
     */
    ALREADY_RECOVERED,

    /**
     * The device was found alive, so no replacement was made.
     *
     * <p>A failure event describes what the gateway saw when it fired, not
     * what is true now. A device whose heartbeat resumed before the operator
     * got to the event has recovered on its own, and replacing it would kill
     * a working device.
     */
    NOT_NEEDED,

    /** The cluster refused, or could not be reached. */
    FAILED
}
