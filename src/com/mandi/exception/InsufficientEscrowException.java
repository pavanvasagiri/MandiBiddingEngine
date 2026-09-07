package com.mandi.exception;

import java.math.BigDecimal;

/**
 * Thrown inside a settlement transaction when the buyer's escrow balance
 * (locked via SELECT ... FOR UPDATE) cannot cover the total debit. The
 * transaction is rolled back, leaving lot, buyer and ledger rows untouched.
 */
public class InsufficientEscrowException extends RuntimeException {

    private final int buyerId;
    private final BigDecimal required;
    private final BigDecimal available;

    public InsufficientEscrowException(int buyerId, BigDecimal required, BigDecimal available) {
        super(String.format("Buyer %d has escrow Rs.%s but the settlement requires Rs.%s",
                buyerId, available, required));
        this.buyerId = buyerId;
        this.required = required;
        this.available = available;
    }

    public int getBuyerId() {
        return buyerId;
    }

    public BigDecimal getRequired() {
        return required;
    }

    public BigDecimal getAvailable() {
        return available;
    }
}
