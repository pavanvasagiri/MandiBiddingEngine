package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A buyer trading in the mandi. Purchase money is held against an
 * escrow balance that the settlement transaction debits under a
 * pessimistic row lock (see SettlementDAO.processSettlement).
 */
public final class Buyer {

    private volatile Integer buyerId; // assigned by the database (AUTO_INCREMENT)
    private final String name;
    private final BigDecimal escrowBalance;

    public Buyer(String name, BigDecimal escrowBalance) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("buyer name is required");
        if (escrowBalance == null || escrowBalance.signum() < 0) throw new IllegalArgumentException("escrow balance must be zero or positive");
        this.name = name.trim();
        this.escrowBalance = escrowBalance.setScale(2, RoundingMode.HALF_UP);
    }

    public Integer getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Integer buyerId) {
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
    }

    public String getName() {
        return name;
    }

    public BigDecimal getEscrowBalance() {
        return escrowBalance;
    }

    @Override
    public String toString() {
        return name + " (escrow Rs." + escrowBalance + ")";
    }
}
