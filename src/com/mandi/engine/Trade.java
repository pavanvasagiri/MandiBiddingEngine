package com.mandi.engine;

import com.mandi.model.Settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One executed trade produced by the clearing phase: the highest bid crossed
 * with the lowest ask for a given quantity at the midpoint clearing price
 * {@code (bidPrice + askPrice) / 2}. Immutable value object.
 */
public record Trade(
        int cropId,
        String cropName,
        int lotId,
        int bidId,
        int buyerId,
        String buyerName,
        int farmerId,
        String farmerName,
        BigDecimal clearingPricePerQuintal,
        BigDecimal quantityQuintals) {

    public Trade {
        if (clearingPricePerQuintal == null || clearingPricePerQuintal.signum() <= 0) {
            throw new IllegalArgumentException("clearing price must be positive");
        }
        if (quantityQuintals == null || quantityQuintals.signum() <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive");
        }
        clearingPricePerQuintal = clearingPricePerQuintal.setScale(2, RoundingMode.HALF_UP);
        quantityQuintals = quantityQuintals.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal grossAmount() {
        return clearingPricePerQuintal.multiply(quantityQuintals).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal mandiTax() {
        return grossAmount().multiply(Settlement.MANDI_TAX_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal buyerDebit() {
        return grossAmount().add(mandiTax());
    }
}
