package com.mandi.model;

/** Outcome of submitting a {@link Bid} to the double-auction engine. */
public enum BidStatus {
    /** Passed the MSP and anomaly screens; queued in the order book. */
    ACCEPTED,
    /** Auto-rejected: price below the crop's Minimum Support Price. */
    REJECTED_BELOW_MSP,
    /** Auto-rejected and flagged: price exceeds the 7-day rolling average by more than 50%. */
    REJECTED_ANOMALY;

    public boolean isAccepted() {
        return this == ACCEPTED;
    }
}
