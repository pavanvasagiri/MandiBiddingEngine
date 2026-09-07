package com.mandi.engine;

import com.mandi.model.Ask;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Farmer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe, crop-scoped <strong>double-auction</strong> matching engine.
 *
 * <p><strong>Order book.</strong> A {@link ConcurrentHashMap} maps each crop id
 * to a {@link MarketBook} holding a {@code PriorityQueue<Bid>} Max-Heap and a
 * {@code PriorityQueue<Ask>} Min-Heap. Queues are guarded by a per-crop
 * {@link java.util.concurrent.locks.ReentrantLock}, so many buyers can place
 * bids concurrently while a clearing pass is atomic per crop.
 *
 * <p><strong>MSP floor.</strong> Any bid below the crop's registered Minimum
 * Support Price is auto-rejected ({@link BidStatus#REJECTED_BELOW_MSP}).
 *
 * <p><strong>Anomaly / manipulation screen.</strong> A bid priced above
 * {@link #ANOMALY_THRESHOLD_MULTIPLIER} times the crop's latest 7-day rolling
 * average mandi price (i.e. more than 50% above it) is rejected and flagged
 * ({@link BidStatus#REJECTED_ANOMALY}). Rolling averages are published by the
 * analytics DAO (SQL window functions) before bidding opens.
 *
 * <p><strong>Clearing algorithm.</strong> While the top bid price is
 * {@code >=} the top ask reserve price, the pair trades
 * {@code min(bidQty, askQty)} quintals at the equilibrium clearing price
 * {@code (bidPrice + askPrice) / 2}. Partially consumed orders are re-queued
 * with their remaining quantity, so a lot can settle through several trades.
 */
public final class DoubleAuctionEngine {

    /** A bid above rolling-average x 1.50 is treated as price manipulation. */
    public static final BigDecimal ANOMALY_THRESHOLD_MULTIPLIER = new BigDecimal("1.50");

    private static final BigDecimal TWO = new BigDecimal("2");

    private final ConcurrentMap<Integer, MarketBook> books = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Crop> cropRegistry = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Buyer> buyerRegistry = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Farmer> farmerRegistry = new ConcurrentHashMap<>();
    private volatile Map<Integer, BigDecimal> rolling7DayAverageByCrop = Map.of();

    // ------------------------------------------------------------------
    // Registry loading (done once, after seeding, before bidding opens)
    // ------------------------------------------------------------------

    public void loadCrops(Collection<Crop> crops) {
        Objects.requireNonNull(crops, "crops");
        crops.forEach(c -> cropRegistry.put(c.getCropId(), c));
    }

    public void loadBuyers(Collection<Buyer> buyers) {
        Objects.requireNonNull(buyers, "buyers");
        buyers.forEach(b -> buyerRegistry.put(b.getBuyerId(), b));
    }

    public void loadFarmers(Collection<Farmer> farmers) {
        Objects.requireNonNull(farmers, "farmers");
        farmers.forEach(f -> farmerRegistry.put(f.getFarmerId(), f));
    }

    /** Publishes the latest 7-day rolling averages (per crop) used by the anomaly screen. */
    public void publishRollingAverages(Map<Integer, BigDecimal> rollingAverages) {
        this.rolling7DayAverageByCrop = rollingAverages == null ? Map.of() : Map.copyOf(rollingAverages);
    }

    // ------------------------------------------------------------------
    // Order entry
    // ------------------------------------------------------------------

    /**
     * Screens and queues a buyer bid. Blocking only on the per-crop lock;
     * safe to call from many buyer threads simultaneously.
     */
    public BidPlacementResult placeBid(Bid bid) {
        Objects.requireNonNull(bid, "bid");
        Crop crop = cropRegistry.get(bid.getCropId());
        if (crop == null) {
            throw new IllegalArgumentException("Unknown crop id " + bid.getCropId() + "; load the crop registry first");
        }

        // 1) Minimum Support Price floor - auto-reject anything below MSP.
        if (bid.getBidPricePerQuintal().compareTo(crop.getMspPricePerQuintal()) < 0) {
            return BidPlacementResult.rejectedBelowMsp(bid, crop.getMspPricePerQuintal());
        }

        // 2) Price-manipulation screen against the 7-day rolling average.
        BigDecimal rollingAverage = rolling7DayAverageByCrop.get(bid.getCropId());
        if (rollingAverage != null && rollingAverage.signum() > 0) {
            BigDecimal ceiling = rollingAverage.multiply(ANOMALY_THRESHOLD_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
            if (bid.getBidPricePerQuintal().compareTo(ceiling) > 0) {
                return BidPlacementResult.rejectedAnomaly(bid, rollingAverage);
            }
        }

        // 3) Queue into the crop's Max-Heap.
        MarketBook book = book(bid.getCropId());
        book.lock.lock();
        try {
            book.bids.offer(bid);
        } finally {
            book.lock.unlock();
        }
        return BidPlacementResult.accepted(bid);
    }

    /** Queues a farmer ask (its reserve price becomes the Min-Heap key). */
    public void placeAsk(Ask ask) {
        Objects.requireNonNull(ask, "ask");
        if (!cropRegistry.containsKey(ask.getCropId())) {
            throw new IllegalArgumentException("Unknown crop id " + ask.getCropId() + "; load the crop registry first");
        }
        MarketBook book = book(ask.getCropId());
        book.lock.lock();
        try {
            book.asks.offer(ask);
        } finally {
            book.lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Clearing
    // ------------------------------------------------------------------

    /** Clears every registered crop and returns all executed trades. */
    public List<Trade> clearAllMarkets() {
        List<Trade> allTrades = new ArrayList<>();
        List<Integer> cropIds = new ArrayList<>(books.keySet());
        Collections.sort(cropIds); // deterministic clearing order
        for (Integer cropId : cropIds) {
            allTrades.addAll(clearCrop(cropId));
        }
        return allTrades;
    }

    /**
     * Runs the double-auction clearing loop for one crop. While the best bid
     * crosses the best ask, both sides trade at the midpoint price; partially
     * filled orders return to their heap with the remaining quantity.
     */
    public List<Trade> clearCrop(int cropId) {
        MarketBook book = books.get(cropId);
        if (book == null) {
            return List.of();
        }
        Crop crop = cropRegistry.get(cropId);
        String cropName = crop != null ? crop.getCropName() : ("Crop#" + cropId);

        List<Trade> trades = new ArrayList<>();
        book.lock.lock();
        try {
            while (true) {
                Bid bid = book.bids.peek();
                Ask ask = book.asks.peek();
                if (bid == null || ask == null) break;
                if (bid.getBidPricePerQuintal().compareTo(ask.getReservePricePerQuintal()) < 0) break; // book no longer crossed

                BigDecimal tradedQuantity = bid.getQuantityQuintals().min(ask.getQuantityQuintals());
                BigDecimal clearingPrice = bid.getBidPricePerQuintal()
                        .add(ask.getReservePricePerQuintal())
                        .divide(TWO, 2, RoundingMode.HALF_UP);

                trades.add(new Trade(cropId, cropName,
                        requirePositiveId(ask.getLotId(), "lotId"),
                        bid.getBidId() == null ? 0 : bid.getBidId(),
                        bid.getBuyerId(), buyerName(bid.getBuyerId()),
                        ask.getFarmerId(), farmerName(ask.getFarmerId()),
                        clearingPrice, tradedQuantity));

                book.bids.poll();
                book.asks.poll();

                BigDecimal bidRemaining = bid.getQuantityQuintals().subtract(tradedQuantity);
                BigDecimal askRemaining = ask.getQuantityQuintals().subtract(tradedQuantity);
                if (bidRemaining.signum() > 0) {
                    book.bids.offer(bid.withRemainingQuantity(bidRemaining));
                }
                if (askRemaining.signum() > 0) {
                    book.asks.offer(ask.withRemainingQuantity(askRemaining));
                }
            }
        } finally {
            book.lock.unlock();
        }
        return trades;
    }

    // ------------------------------------------------------------------
    // Introspection / reporting
    // ------------------------------------------------------------------

    public int pendingBidCount(int cropId) {
        MarketBook book = books.get(cropId);
        if (book == null) return 0;
        book.lock.lock();
        try {
            return book.bids.size();
        } finally {
            book.lock.unlock();
        }
    }

    public int pendingAskCount(int cropId) {
        MarketBook book = books.get(cropId);
        if (book == null) return 0;
        book.lock.lock();
        try {
            return book.asks.size();
        } finally {
            book.lock.unlock();
        }
    }

    public List<Integer> registeredCropIds() {
        List<Integer> ids = new ArrayList<>(cropRegistry.keySet());
        Collections.sort(ids);
        return ids;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MarketBook book(int cropId) {
        return books.computeIfAbsent(cropId, id -> new MarketBook());
    }

    private String buyerName(int buyerId) {
        Buyer buyer = buyerRegistry.get(buyerId);
        return buyer != null ? buyer.getName() : ("Buyer#" + buyerId);
    }

    private String farmerName(int farmerId) {
        Farmer farmer = farmerRegistry.get(farmerId);
        return farmer != null ? farmer.getName() : ("Farmer#" + farmerId);
    }

    private static int requirePositiveId(Integer id, String field) {
        if (id == null || id <= 0) {
            throw new IllegalStateException(field + " must be a persisted database id before clearing");
        }
        return id;
    }
}
