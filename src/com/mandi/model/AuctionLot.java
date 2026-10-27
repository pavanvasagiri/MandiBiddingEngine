package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A physical lot of produce consigned by a farmer to the mandi. Each lot
 * becomes one {@link Ask} (at its reserve price) in the double-auction book.
 * The settlement transaction accumulates {@code filledQuantity} and flips the
 * status to {@link LotStatus#SETTLED} once the lot is fully consumed.
 */
public final class AuctionLot {

    private volatile Integer lotId; // assigned by the database (AUTO_INCREMENT)
    private final int cropId;
    private final int farmerId;
    private final BigDecimal quantityQuintals;
    private final BigDecimal reservePricePerQuintal;
    private volatile BigDecimal filledQuantity = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private volatile LotStatus status = LotStatus.OPEN;
    private final Instant createdAt;

    public AuctionLot(int cropId, int farmerId, BigDecimal quantityQuintals, BigDecimal reservePricePerQuintal) {
        this(cropId, farmerId, quantityQuintals, reservePricePerQuintal, Instant.now());
    }

    public AuctionLot(int cropId, int farmerId, BigDecimal quantityQuintals, BigDecimal reservePricePerQuintal, Instant createdAt) {
        if (quantityQuintals == null || reservePricePerQuintal == null) {
            throw new IllegalArgumentException("quantity and reserve price are required");
        }
        if (quantityQuintals.signum() <= 0) throw new IllegalArgumentException("lot quantity must be positive");
        if (reservePricePerQuintal.signum() <= 0) throw new IllegalArgumentException("reserve price must be positive");
        this.cropId = cropId;
        this.farmerId = farmerId;
        this.quantityQuintals = quantityQuintals.setScale(2, RoundingMode.HALF_UP);
        this.reservePricePerQuintal = reservePricePerQuintal.setScale(2, RoundingMode.HALF_UP);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Integer getLotId() {
        return lotId;
    }

    public void setLotId(Integer lotId) {
        this.lotId = lotId;
    }

    public int getCropId() {
        return cropId;
    }

    public int getFarmerId() {
        return farmerId;
    }

    public BigDecimal getQuantityQuintals() {
        return quantityQuintals;
    }

    public BigDecimal getReservePricePerQuintal() {
        return reservePricePerQuintal;
    }

    public BigDecimal getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(BigDecimal filledQuantity) {
        this.filledQuantity = filledQuantity.setScale(2, RoundingMode.HALF_UP);
    }

    public LotStatus getStatus() {
        return status;
    }

    public void setStatus(LotStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "AuctionLot{" + (lotId == null ? "unpersisted" : "lotId=" + lotId)
                + ", cropId=" + cropId + ", farmerId=" + farmerId
                + ", qty=" + quantityQuintals + "q, reserve=" + reservePricePerQuintal
                + ", filled=" + filledQuantity + "q, status=" + status + '}';
    }
}
