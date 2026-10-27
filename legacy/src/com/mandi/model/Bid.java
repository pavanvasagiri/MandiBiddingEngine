package com.mandi.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Bid(
    String bidId,
    String lotId,
    String buyerId,
    BigDecimal pricePerQuintal,
    BigDecimal quantityQuintals,
    LocalDateTime timestamp
) implements Comparable<Bid> {

    public Bid {
        OrderValidation.requireId(bidId, "bidId");
        OrderValidation.requireId(lotId, "lotId");
        OrderValidation.requireId(buyerId, "buyerId");
        OrderValidation.requirePositive(pricePerQuintal, "pricePerQuintal");
        OrderValidation.requirePositive(quantityQuintals, "quantityQuintals");
        if (timestamp == null) throw new IllegalArgumentException("timestamp is required");
    }

    public Bid withQuantity(BigDecimal remainingQuantity) {
        return new Bid(bidId, lotId, buyerId, pricePerQuintal, remainingQuantity, timestamp);
    }

    @Override
    public int compareTo(Bid other) {
        // Higher bid price gets top priority in Max-Heap
        int priceCompare = other.pricePerQuintal.compareTo(this.pricePerQuintal);
        if (priceCompare == 0) {
            // First-come, first-served time priority on price tie
            return this.timestamp.compareTo(other.timestamp);
        }
        return priceCompare;
    }
}
