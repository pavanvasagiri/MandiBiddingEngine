package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * The financial settlement of one trade (one matched bid/ask pair) on one
 * auction lot.
 *
 * <p>Money model (APMC-style): the buyer pays the gross value plus a 2%
 * mandi market fee; the farmer receives the full gross value as payout.
 * All computations use {@link BigDecimal} with {@code HALF_UP} rounding at
 * 2 decimal places. A Settlement is persisted inside a
 * {@link java.sql.Connection#TRANSACTION_SERIALIZABLE} transaction with
 * pessimistic row locks - see {@code com.mandi.dao.SettlementDAO}.
 */
public final class Settlement {

    /** Mandi market fee rate applied on the gross trade value (2%), borne by the buyer. */
    public static final BigDecimal MANDI_TAX_RATE = new BigDecimal("0.02");

    private volatile Integer settlementId; // assigned by the database inside the settlement transaction
    private final int lotId;
    private final int bidId;
    private final int buyerId;
    private final int farmerId;
    private final int cropId;
    private final BigDecimal clearingPricePerQuintal;
    private final BigDecimal quantityQuintals;
    private final BigDecimal grossAmount;
    private final BigDecimal mandiTax;
    private final BigDecimal buyerDebit;
    private final BigDecimal farmerPayout;
    private final Instant settledAt;

    public Settlement(int lotId, int bidId, int buyerId, int farmerId, int cropId,
                      BigDecimal clearingPricePerQuintal, BigDecimal quantityQuintals,
                      BigDecimal grossAmount, BigDecimal mandiTax,
                      BigDecimal buyerDebit, BigDecimal farmerPayout, Instant settledAt) {
        this.lotId = lotId;
        this.bidId = bidId;
        this.buyerId = buyerId;
        this.farmerId = farmerId;
        this.cropId = cropId;
        this.clearingPricePerQuintal = clearingPricePerQuintal.setScale(2, RoundingMode.HALF_UP);
        this.quantityQuintals = quantityQuintals.setScale(2, RoundingMode.HALF_UP);
        this.grossAmount = grossAmount.setScale(2, RoundingMode.HALF_UP);
        this.mandiTax = mandiTax.setScale(2, RoundingMode.HALF_UP);
        this.buyerDebit = buyerDebit.setScale(2, RoundingMode.HALF_UP);
        this.farmerPayout = farmerPayout.setScale(2, RoundingMode.HALF_UP);
        this.settledAt = settledAt == null ? Instant.now() : settledAt;
    }

    /**
     * Factory that derives every financial figure from the clearing price and
     * traded quantity, keeping the tax rule in exactly one place.
     */
    public static Settlement newSettlement(int lotId, int bidId, int buyerId, int farmerId, int cropId,
                                           BigDecimal clearingPricePerQuintal, BigDecimal quantityQuintals) {
        BigDecimal gross = clearingPricePerQuintal.multiply(quantityQuintals).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = gross.multiply(MANDI_TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        return new Settlement(lotId, bidId, buyerId, farmerId, cropId,
                clearingPricePerQuintal, quantityQuintals, gross, tax,
                gross.add(tax), gross, Instant.now());
    }

    public Integer getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Integer settlementId) {
        this.settlementId = settlementId;
    }

    public int getLotId() {
        return lotId;
    }

    public int getBidId() {
        return bidId;
    }

    public int getBuyerId() {
        return buyerId;
    }

    public int getFarmerId() {
        return farmerId;
    }

    public int getCropId() {
        return cropId;
    }

    public BigDecimal getClearingPricePerQuintal() {
        return clearingPricePerQuintal;
    }

    public BigDecimal getQuantityQuintals() {
        return quantityQuintals;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getMandiTax() {
        return mandiTax;
    }

    public BigDecimal getBuyerDebit() {
        return buyerDebit;
    }

    public BigDecimal getFarmerPayout() {
        return farmerPayout;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    @Override
    public String toString() {
        return "Settlement{" + (settlementId == null ? "uncommitted" : "settlementId=" + settlementId)
                + ", lotId=" + lotId + ", bidId=" + bidId + ", buyerId=" + buyerId
                + ", farmerId=" + farmerId + ", cropId=" + cropId
                + ", clearingPrice=" + clearingPricePerQuintal + ", qty=" + quantityQuintals
                + ", gross=" + grossAmount + ", mandiTax=" + mandiTax
                + ", buyerDebit=" + buyerDebit + ", farmerPayout=" + farmerPayout
                + ", settledAt=" + settledAt + '}';
    }
}
