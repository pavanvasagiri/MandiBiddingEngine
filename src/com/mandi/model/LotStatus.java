package com.mandi.model;

/** Lifecycle of an {@link AuctionLot} inside the mandi. */
public enum LotStatus {
    /** Consigned and open for bidding. */
    OPEN,
    /** At least one trade matched against the lot, quantity not yet exhausted. */
    MATCHED,
    /** Fully traded and financially settled. */
    SETTLED,
    /** Withdrawn or expired without full settlement. */
    CANCELLED
}
