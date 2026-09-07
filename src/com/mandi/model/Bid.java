package com.mandi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * A buyer's bid in the double-auction order book.
 *
 * <p>Thread-safety: every field is {@code final} except the database-assigned
 * id, which is {@code volatile}; all held types ({@link BigDecimal},
 * {@link Instant}) are immutable. A Bid is therefore safely publishable
 * across threads. Monetary amounts and quantities use {@link BigDecimal}
 * exclusively (never {@code double}/{@code float}).
 *
 * <p>Natural ordering matches a Max-Heap ({@code PriorityQueue<Bid>}):
 * the <strong>highest</strong> price is polled first; on a price tie the
 * <strong>earliest</strong> timestamp wins (strict time priority).
 */
public final class Bid implements Comparable<Bid> {

    private volatile Integer bidId; // assigned by BidDAO after the JDBC insert (generated key)

    private final int cropId;
    private final int buyerId;
    private final BigDecimal bidPricePerQuintal;
    private final BigDecimal quantityQuintals;
    private final Instant placedAt;

    public Bid(int cropId, int buyerId, BigDecimal bidPricePerQuintal, BigDecimal quantityQuintals) {
        this(cropId, buyerId, bidPricePerQuintal, quantityQuintals, Instant.now());
    }

    public Bid(int cropId, int buyerId, BigDecimal bidPricePerQuintal, BigDecimal quantityQuintals, Instant placedAt) {
        if (bidPricePerQuintal == null || quantityQuintals == null) {
            throw new IllegalArgumentException("bid price and quantity are required");
        }
        if (bidPricePerQuintal.signum() <= 0) {
            throw new IllegalArgumentException("bid price must be positive");
        }
        if (quantityQuintals.signum() <= 0) {
            throw new IllegalArgumentException("bid quantity must be positive");
        }
        this.cropId = cropId;
        this.buyerId = buyerId;
        this.bidPricePerQuintal = bidPricePerQuintal.setScale(2, RoundingMode.HALF_UP);
        this.quantityQuintals = quantityQuintals.setScale(2, RoundingMode.HALF_UP);
        this.placedAt = placedAt == null ? Instant.now() : placedAt;
    }

    public Integer getBidId() {
        return bidId;
    }

    /** Called by the persistence layer once MySQL has assigned the generated key. */
    public void setBidId(Integer bidId) {
        this.bidId = Objects.requireNonNull(bidId, "bidId");
    }

    public int getCropId() {
        return cropId;
    }

    public int getBuyerId() {
        return buyerId;
    }

    public BigDecimal getBidPricePerQuintal() {
        return bidPricePerQuintal;
    }

    public BigDecimal getQuantityQuintals() {
        return quantityQuintals;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    /** Copy with a reduced open quantity, keeping identity (id, price, timestamp). */
    public Bid withRemainingQuantity(BigDecimal remainingQuantity) {
        Bid copy = new Bid(cropId, buyerId, bidPricePerQuintal, remainingQuantity, placedAt);
        copy.bidId = this.bidId;
        return copy;
    }

    @Override
    public int compareTo(Bid other) {
        int priceCompare = other.bidPricePerQuintal.compareTo(this.bidPricePerQuintal); // inverted: Max-Heap
        return priceCompare != 0 ? priceCompare : this.placedAt.compareTo(other.placedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bid other)) return false;
        return cropId == other.cropId
                && buyerId == other.buyerId
                && bidPricePerQuintal.compareTo(other.bidPricePerQuintal) == 0
                && quantityQuintals.compareTo(other.quantityQuintals) == 0
                && placedAt.equals(other.placedAt)
                && Objects.equals(bidId, other.bidId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cropId, buyerId, bidPricePerQuintal.stripTrailingZeros(), quantityQuintals.stripTrailingZeros(), placedAt, bidId);
    }

    @Override
    public String toString() {
        return "Bid{" + (bidId == null ? "unpersisted" : "bidId=" + bidId)
                + ", cropId=" + cropId + ", buyerId=" + buyerId
                + ", price=" + bidPricePerQuintal + "/quintal, qty=" + quantityQuintals
                + ", placedAt=" + placedAt + '}';
    }
}
