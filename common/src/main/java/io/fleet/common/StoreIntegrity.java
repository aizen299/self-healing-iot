package io.fleet.common;

/**
 * Whether a stretch of history is complete, and how badly it is not.
 *
 * <p>Exists because the gateway deliberately keeps running when the store
 * fails — losing history is bad, losing failure detection is worse. The
 * consequence is that a run can finish with holes in its record while every
 * figure derived from it looks entirely normal, and a fleet average over a
 * window with a thousand missing readings reads exactly like one over a
 * complete window.
 *
 * <p>So the gap count travels with the data rather than living in a log line
 * somebody has to remember to check. Every query result carries this, the run
 * summary prints it, and the reproducibility contract has something concrete
 * to test: a recorded experiment whose window is not {@link #isComplete()} is
 * not a result.
 *
 * @param droppedWrites   readings known to have been lost in this window
 * @param dropEvents      distinct failures that lost them
 * @param lastDropAtMillis when the most recent loss happened; 0 if none
 */
public record StoreIntegrity(long droppedWrites, long dropEvents, long lastDropAtMillis) {

    public static final StoreIntegrity COMPLETE = new StoreIntegrity(0L, 0L, 0L);

    /** True when nothing is known to be missing from this window. */
    public boolean isComplete() {
        return droppedWrites == 0L;
    }

    /**
     * A one-line description for a run summary or a report.
     *
     * <p>Deliberately blunt when incomplete: the whole point is that this is
     * hard to read past.
     */
    public String describe() {
        return isComplete()
                ? "complete"
                : "INCOMPLETE — " + droppedWrites + " readings lost across "
                        + dropEvents + " failures";
    }
}
