package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * A farmer's ask (offer to sell) in the double-auction order book. An ask is
 * backed by a physical {@link AuctionLot}; {@code lotId} links the two.
 *
 * <p>Thread-safety: identical to {@link Bid} - final immutable state plus a
 * volatile database-assigned id. Natural ordering matches a Min-Heap
 * ({@code PriorityQueue<Ask>}): the <strong>lowest</strong> reserve price is
 * polled first; on a tie the <strong>earliest</strong> timestamp wins.
 */
public final class Ask implements Comparable<Ask> {

    private volatile Integer askId; // reserved for future persistence of the ask book itself

    private final int lotId;
    private final int cropId;
    private final int farmerId;
    private final BigDecimal reservePricePerQuintal;
    private final BigDecimal quantityQuintals;
    private final Instant placedAt;

    public Ask(int lotId, int cropId, int farmerId, BigDecimal reservePricePerQuintal, BigDecimal quantityQuintals) {
        this(lotId, cropId, farmerId, reservePricePerQuintal, quantityQuintals, Instant.now());
    }

    public Ask(int lotId, int cropId, int farmerId, BigDecimal reservePricePerQuintal, BigDecimal quantityQuintals, Instant placedAt) {
        if (reservePricePerQuintal == null || quantityQuintals == null) {
            throw new IllegalArgumentException("reserve price and quantity are required");
        }
        if (reservePricePerQuintal.signum() <= 0) {
            throw new IllegalArgumentException("reserve price must be positive");
        }
        if (quantityQuintals.signum() <= 0) {
            throw new IllegalArgumentException("ask quantity must be positive");
        }
        this.lotId = lotId;
        this.cropId = cropId;
        this.farmerId = farmerId;
        this.reservePricePerQuintal = reservePricePerQuintal.setScale(2, RoundingMode.HALF_UP);
        this.quantityQuintals = quantityQuintals.setScale(2, RoundingMode.HALF_UP);
        this.placedAt = placedAt == null ? Instant.now() : placedAt;
    }

    public Integer getAskId() {
        return askId;
    }

    public void setAskId(Integer askId) {
        this.askId = Objects.requireNonNull(askId, "askId");
    }

    public int getLotId() {
        return lotId;
    }

    public int getCropId() {
        return cropId;
    }

    public int getFarmerId() {
        return farmerId;
    }

    public BigDecimal getReservePricePerQuintal() {
        return reservePricePerQuintal;
    }

    public BigDecimal getQuantityQuintals() {
        return quantityQuintals;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    /** Copy with a reduced open quantity, keeping identity (lot, crop, farmer, price, timestamp). */
    public Ask withRemainingQuantity(BigDecimal remainingQuantity) {
        Ask copy = new Ask(lotId, cropId, farmerId, reservePricePerQuintal, remainingQuantity, placedAt);
        copy.askId = this.askId;
        return copy;
    }

    @Override
    public int compareTo(Ask other) {
        int priceCompare = this.reservePricePerQuintal.compareTo(other.reservePricePerQuintal); // natural: Min-Heap
        return priceCompare != 0 ? priceCompare : this.placedAt.compareTo(other.placedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Ask other)) return false;
        return lotId == other.lotId
                && cropId == other.cropId
                && farmerId == other.farmerId
                && reservePricePerQuintal.compareTo(other.reservePricePerQuintal) == 0
                && quantityQuintals.compareTo(other.quantityQuintals) == 0
                && placedAt.equals(other.placedAt)
                && Objects.equals(askId, other.askId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lotId, cropId, farmerId, reservePricePerQuintal.stripTrailingZeros(), quantityQuintals.stripTrailingZeros(), placedAt, askId);
    }

    @Override
    public String toString() {
        return "Ask{lotId=" + lotId + ", cropId=" + cropId + ", farmerId=" + farmerId
                + ", reserve=" + reservePricePerQuintal + "/quintal, qty=" + quantityQuintals
                + ", placedAt=" + placedAt + '}';
    }
}
