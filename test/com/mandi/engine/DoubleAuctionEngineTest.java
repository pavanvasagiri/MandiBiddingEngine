package com.mandi.engine;

import com.mandi.model.Ask;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Farmer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Standalone regression tests for the double-auction engine (no JUnit
 * dependency - run with: java -cp out com.mandi.engine.DoubleAuctionEngineTest).
 * Covers heap ordering, the MSP floor, the anomaly screen and the clearing
 * algorithm including partial fills.
 */
public final class DoubleAuctionEngineTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testBidMaxHeapOrder();
        testAskMinHeapOrder();
        testMspRejection();
        testAnomalyRejection();
        testClearingMidpointAndPartialFill();
        if (failures > 0) {
            System.err.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("All engine tests passed.");
    }

    private static void testBidMaxHeapOrder() {
        Bid high = new Bid(1, 1, new BigDecimal("2400.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T10:00:00Z"));
        Bid midEarly = new Bid(1, 2, new BigDecimal("2350.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T09:00:00Z"));
        Bid midLate = new Bid(1, 3, new BigDecimal("2350.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T11:00:00Z"));
        check(high.compareTo(midEarly) < 0, "higher bid price must sort first (max-heap)");
        check(midEarly.compareTo(midLate) < 0, "earlier timestamp must win a price tie");
        check(midLate.compareTo(midEarly) > 0, "later timestamp must sort after earlier");
    }

    private static void testAskMinHeapOrder() {
        Ask cheap = new Ask(1, 1, 1, new BigDecimal("2300.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T11:00:00Z"));
        Ask cheapEarly = new Ask(2, 1, 2, new BigDecimal("2300.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T09:00:00Z"));
        Ask pricey = new Ask(3, 1, 3, new BigDecimal("2280.00"), new BigDecimal("10.00"), Instant.parse("2026-09-04T11:00:00Z"));
        check(pricey.compareTo(cheap) < 0, "lower reserve price must sort first (min-heap)");
        check(cheapEarly.compareTo(cheap) < 0, "earlier timestamp must win a reserve tie");
    }

    private static void testMspRejection() {
        DoubleAuctionEngine engine = newEngine();
        Bid belowMsp = new Bid(1, 1, new BigDecimal("2200.00"), new BigDecimal("5.00"));
        BidPlacementResult result = engine.placeBid(belowMsp);
        check(result.status() == BidStatus.REJECTED_BELOW_MSP, "bid below MSP must be rejected");
        check(engine.pendingBidCount(1) == 0, "rejected bid must not enter the book");
        check(engine.placeBid(new Bid(1, 1, new BigDecimal("2275.00"), new BigDecimal("5.00"))).status() == BidStatus.ACCEPTED,
                "bid exactly at MSP must be accepted");
    }

    private static void testAnomalyRejection() {
        DoubleAuctionEngine engine = newEngine();
        engine.publishRollingAverages(Map.of(1, new BigDecimal("2300.00"))); // ceiling = 3450.00
        check(engine.placeBid(new Bid(1, 1, new BigDecimal("3450.00"), new BigDecimal("5.00"))).status() == BidStatus.ACCEPTED,
                "bid exactly at 150% of the rolling average is allowed");
        BidPlacementResult flagged = engine.placeBid(new Bid(1, 2, new BigDecimal("3600.00"), new BigDecimal("5.00")));
        check(flagged.status() == BidStatus.REJECTED_ANOMALY, "bid above 150% of the rolling average must be flagged");
        check(engine.pendingBidCount(1) == 1, "flagged bid must not enter the book");
    }

    private static void testClearingMidpointAndPartialFill() {
        DoubleAuctionEngine engine = newEngine();
        engine.placeAsk(new Ask(1, 1, 1, new BigDecimal("2300.00"), new BigDecimal("10.00")));
        engine.placeBid(new Bid(1, 1, new BigDecimal("2500.00"), new BigDecimal("6.00")));
        engine.placeBid(new Bid(1, 2, new BigDecimal("2340.00"), new BigDecimal("12.00")));

        List<Trade> trades = engine.clearAllMarkets();
        check(trades.size() == 2, "two crossing pairs must trade, got " + trades.size());
        Trade first = trades.get(0);
        check(first.buyerId() == 1 && first.clearingPricePerQuintal().compareTo(new BigDecimal("2400.00")) == 0
                        && first.quantityQuintals().compareTo(new BigDecimal("6.00")) == 0,
                "highest bid must clear first at (2500+2300)/2 = 2400.00 for 6.00q");
        Trade second = trades.get(1);
        check(second.buyerId() == 2 && second.clearingPricePerQuintal().compareTo(new BigDecimal("2320.00")) == 0
                        && second.quantityQuintals().compareTo(new BigDecimal("4.00")) == 0,
                "remaining ask must clear at (2340+2300)/2 = 2320.00 for the 4.00q remainder");
        check(engine.pendingAskCount(1) == 0 && engine.pendingBidCount(1) == 1,
                "ask fully consumed; bid retains 8.00q open quantity");
        check(engine.clearAllMarkets().isEmpty(), "uncrossable remainder must not trade");
    }

    private static DoubleAuctionEngine newEngine() {
        DoubleAuctionEngine engine = new DoubleAuctionEngine();
        Crop crop = new Crop("Wheat (Gehun)", new BigDecimal("2275.00"));
        crop.setCropId(1);
        engine.loadCrops(List.of(crop));
        Buyer buyer = new Buyer("Test Buyer", new BigDecimal("100000.00"));
        buyer.setBuyerId(1);
        Buyer buyer2 = new Buyer("Test Buyer 2", new BigDecimal("100000.00"));
        buyer2.setBuyerId(2);
        engine.loadBuyers(List.of(buyer, buyer2));
        Farmer farmer = new Farmer("Test Farmer", "Test Village", "ACC1234567890");
        farmer.setFarmerId(1);
        Farmer farmer2 = new Farmer("Test Farmer 2", "Test Village", "ACC1234567891");
        farmer2.setFarmerId(2);
        engine.loadFarmers(List.of(farmer, farmer2));
        return engine;
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("PASS  " + message);
        } else {
            failures++;
            System.err.println("FAIL  " + message);
        }
    }
}
