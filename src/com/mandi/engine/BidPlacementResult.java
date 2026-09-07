package com.mandi.engine;

import com.mandi.model.Bid;
import com.mandi.model.BidStatus;

import java.math.BigDecimal;

/** Result of submitting a {@link Bid} to the engine: the verdict plus the reference price used to decide it. */
public record BidPlacementResult(Bid bid, BidStatus status, String reason, BigDecimal referencePrice) {

    public static BidPlacementResult accepted(Bid bid) {
        return new BidPlacementResult(bid, BidStatus.ACCEPTED, "Queued in the order book", null);
    }

    public static BidPlacementResult rejectedBelowMsp(Bid bid, BigDecimal msp) {
        return new BidPlacementResult(bid, BidStatus.REJECTED_BELOW_MSP,
                "Below Minimum Support Price", msp);
    }

    public static BidPlacementResult rejectedAnomaly(Bid bid, BigDecimal rollingAverage) {
        return new BidPlacementResult(bid, BidStatus.REJECTED_ANOMALY,
                "Exceeds the 7-day rolling average by more than 50% (manipulation flag)", rollingAverage);
    }

    public boolean accepted() {
        return status.isAccepted();
    }
}
