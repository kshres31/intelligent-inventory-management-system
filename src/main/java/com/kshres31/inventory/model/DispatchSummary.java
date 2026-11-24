package com.kshres31.inventory.model;

/**
 * Outcome of one dispatcher pass.
 *
 * @param claimed notifications leased by this pass
 * @param sent    delivered successfully
 * @param retried failed and rescheduled
 * @param dead    failed for the last allowed time
 * @param lost    outcome could not be recorded because the lease had expired and
 *                another dispatcher reclaimed the row
 */
public record DispatchSummary(int claimed, int sent, int retried, int dead, int lost) {
    public static DispatchSummary empty() {
        return new DispatchSummary(0, 0, 0, 0, 0);
    }

    public DispatchSummary plus(DispatchSummary other) {
        return new DispatchSummary(
                claimed + other.claimed, sent + other.sent, retried + other.retried,
                dead + other.dead, lost + other.lost
        );
    }
}
