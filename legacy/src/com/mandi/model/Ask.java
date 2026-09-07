package com.mandi.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Ask(
    String askId,
    String lotId,
    String farmerId,
    BigDecimal reservePricePerQuintal,
    BigDecimal quantityQuintals,
    LocalDateTime timestamp
) implements Comparable<Ask> {

    public Ask {
        OrderValidation.requireId(askId, "askId");
        OrderValidation.requireId(lotId, "lotId");
        OrderValidation.requireId(farmerId, "farmerId");
        OrderValidation.requirePositive(reservePricePerQuintal, "reservePricePerQuintal");
        OrderValidation.requirePositive(quantityQuintals, "quantityQuintals");
        if (timestamp == null) throw new IllegalArgumentException("timestamp is required");
    }

    public Ask withQuantity(BigDecimal remainingQuantity) {
        return new Ask(askId, lotId, farmerId, reservePricePerQuintal, remainingQuantity, timestamp);
    }

    @Override
    public int compareTo(Ask other) {
        // Lower ask price gets top priority in Min-Heap
        int priceCompare = this.reservePricePerQuintal.compareTo(other.reservePricePerQuintal);
        if (priceCompare == 0) {
            return this.timestamp.compareTo(other.timestamp);
        }
        return priceCompare;
    }
}
