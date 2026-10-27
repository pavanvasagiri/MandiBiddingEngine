package com.mandi.exception;

/**
 * Thrown when a settlement is attempted on a lot that is not in a
 * settleable state (already SETTLED, CANCELLED, or not found). Guards the
 * idempotency of the SETTLED transition.
 */
public class LotNotSettleableException extends RuntimeException {

    private final int lotId;
    private final String currentStatus;

    public LotNotSettleableException(int lotId, String currentStatus) {
        super(String.format("Lot %d cannot be settled because its current status is '%s'", lotId, currentStatus));
        this.lotId = lotId;
        this.currentStatus = currentStatus;
    }

    public int getLotId() {
        return lotId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
