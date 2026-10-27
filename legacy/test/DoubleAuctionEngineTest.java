package com.mandi.engine;

import com.mandi.model.Ask;
import com.mandi.model.Bid;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Dependency-free regression tests; run with java -ea after compilation. */
public final class DoubleAuctionEngineTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    public static void main(String[] args) {
        preservesPartialFillQuantities();
        retainsOrdersWhenSettlementFails();
        neverCrossMatchesDifferentLots();
        System.out.println("DoubleAuctionEngine tests passed.");
    }

    private static void preservesPartialFillQuantities() {
        DoubleAuctionEngine engine = new DoubleAuctionEngine();
        engine.submitBuyerBid(bid("B1", "LOT1", "BUYER1", "2300", "10"), "CROP_WHEAT_01");
        engine.submitFarmerAsk(ask("A1", "LOT1", "FARMER1", "2200", "4"), "CROP_WHEAT_01");
        engine.submitFarmerAsk(ask("A2", "LOT1", "FARMER1", "2200", "6"), "CROP_WHEAT_01");

        List<DoubleAuctionEngine.MatchResult> matches = engine.clearMarket("CROP_WHEAT_01", match -> true);
        assert matches.size() == 2 : "Expected two partial fills";
        assert matches.get(0).matchedQuantityQuintals().compareTo(new BigDecimal("4")) == 0;
        assert matches.get(1).matchedQuantityQuintals().compareTo(new BigDecimal("6")) == 0;
    }

    private static void retainsOrdersWhenSettlementFails() {
        DoubleAuctionEngine engine = new DoubleAuctionEngine();
        engine.submitBuyerBid(bid("B2", "LOT2", "BUYER2", "2300", "2"), "CROP_WHEAT_01");
        engine.submitFarmerAsk(ask("A3", "LOT2", "FARMER2", "2200", "2"), "CROP_WHEAT_01");

        assert engine.clearMarket("CROP_WHEAT_01", match -> false).isEmpty();
        assert engine.clearMarket("CROP_WHEAT_01", match -> true).size() == 1 : "Failed settlement must not discard orders";
    }

    private static void neverCrossMatchesDifferentLots() {
        DoubleAuctionEngine engine = new DoubleAuctionEngine();
        engine.submitBuyerBid(bid("B3", "LOT3", "BUYER3", "2300", "2"), "CROP_WHEAT_01");
        engine.submitFarmerAsk(ask("A4", "LOT4", "FARMER3", "2200", "2"), "CROP_WHEAT_01");

        assert engine.clearMarket("CROP_WHEAT_01", match -> true).isEmpty();
    }

    private static Bid bid(String id, String lot, String buyer, String price, String quantity) {
        return new Bid(id, lot, buyer, new BigDecimal(price), new BigDecimal(quantity), TIME);
    }

    private static Ask ask(String id, String lot, String farmer, String price, String quantity) {
        return new Ask(id, lot, farmer, new BigDecimal(price), new BigDecimal(quantity), TIME);
    }
}
