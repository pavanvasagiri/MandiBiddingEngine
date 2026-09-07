package com.mandi.engine;

import com.mandi.model.Ask;
import com.mandi.model.Bid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** A thread-safe, lot-scoped double-auction order book. */
public final class DoubleAuctionEngine {
    private final Map<OrderBookKey, OrderBook> books = new HashMap<>();
    private final Map<String, BigDecimal> mspRegistry = new HashMap<>();

    public DoubleAuctionEngine() {
        registerMsp("CROP_WHEAT_01", new BigDecimal("2275.00"));
        registerMsp("CROP_RICE_01", new BigDecimal("2183.00"));
    }

    public synchronized void registerMsp(String cropId, BigDecimal msp) {
        requireCropId(cropId);
        requireMoney(msp, "msp");
        mspRegistry.put(cropId, msp);
    }

    public synchronized boolean submitBuyerBid(Bid bid, String cropId) {
        requireCropId(cropId);
        BigDecimal msp = mspRegistry.get(cropId);
        if (msp == null) throw new IllegalArgumentException("Unknown crop; register its MSP before accepting bids");
        if (bid.pricePerQuintal().compareTo(msp) < 0) {
            throw new IllegalArgumentException("Rejected: bid is below MSP of ₹" + msp + " per quintal");
        }
        book(cropId, bid.lotId()).buyers.add(bid);
        return true;
    }

    public synchronized void submitFarmerAsk(Ask ask, String cropId) {
        requireCropId(cropId);
        if (!mspRegistry.containsKey(cropId)) throw new IllegalArgumentException("Unknown crop; register its MSP before accepting asks");
        book(cropId, ask.lotId()).sellers.add(ask);
    }

    /**
     * Settles matching orders while holding the book lock. An unsuccessful settlement leaves
     * both orders untouched, so an escrow or database failure can be retried safely.
     */
    public synchronized List<MatchResult> clearMarket(String cropId, SettlementProcessor settlementProcessor) {
        requireCropId(cropId);
        if (settlementProcessor == null) throw new IllegalArgumentException("settlementProcessor is required");
        List<MatchResult> completed = new ArrayList<>();
        while (true) {
            Candidate candidate = bestCandidate(cropId);
            if (candidate == null) return completed;
            MatchResult match = candidate.match();
            if (!settlementProcessor.settle(match)) return completed;
            applyFill(candidate.book(), candidate.bid(), candidate.ask(), match.matchedQuantityQuintals());
            completed.add(match);
        }
    }

    private Candidate bestCandidate(String cropId) {
        return books.entrySet().stream()
            .filter(e -> e.getKey().cropId().equals(cropId))
            .map(e -> candidate(e.getValue()))
            .filter(java.util.Objects::nonNull)
            .max(Comparator.comparing(c -> c.bid().pricePerQuintal()))
            .orElse(null);
    }

    private static Candidate candidate(OrderBook book) {
        Bid bid = book.buyers.peek();
        Ask ask = book.sellers.peek();
        if (bid == null || ask == null || bid.pricePerQuintal().compareTo(ask.reservePricePerQuintal()) < 0) return null;
        BigDecimal quantity = bid.quantityQuintals().min(ask.quantityQuintals());
        BigDecimal price = bid.pricePerQuintal().add(ask.reservePricePerQuintal())
            .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        return new Candidate(book, bid, ask, new MatchResult(bid.lotId(), bid.buyerId(), ask.farmerId(), price, quantity));
    }

    private static void applyFill(OrderBook book, Bid bid, Ask ask, BigDecimal quantity) {
        book.buyers.poll();
        book.sellers.poll();
        BigDecimal bidRemaining = bid.quantityQuintals().subtract(quantity);
        BigDecimal askRemaining = ask.quantityQuintals().subtract(quantity);
        if (bidRemaining.signum() > 0) book.buyers.add(bid.withQuantity(bidRemaining));
        if (askRemaining.signum() > 0) book.sellers.add(ask.withQuantity(askRemaining));
    }

    private OrderBook book(String cropId, String lotId) {
        return books.computeIfAbsent(new OrderBookKey(cropId, lotId), ignored -> new OrderBook());
    }

    private static void requireCropId(String cropId) {
        if (cropId == null || !cropId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("cropId must be 1-100 URL-safe characters");
    }

    private static void requireMoney(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.precision() > 18) throw new IllegalArgumentException(field + " must be a positive monetary value");
    }

    public interface SettlementProcessor { boolean settle(MatchResult match); }

    public record MatchResult(String lotId, String buyerId, String farmerId, BigDecimal clearingPricePerQuintal, BigDecimal matchedQuantityQuintals) { }
    private record OrderBookKey(String cropId, String lotId) { }
    private record Candidate(OrderBook book, Bid bid, Ask ask, MatchResult match) { }
    private static final class OrderBook {
        private final PriorityQueue<Bid> buyers = new PriorityQueue<>();
        private final PriorityQueue<Ask> sellers = new PriorityQueue<>();
    }
}
