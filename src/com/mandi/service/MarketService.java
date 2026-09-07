package com.mandi.service;

import com.mandi.dao.AuctionLotDAO;
import com.mandi.dao.BidDAO;
import com.mandi.dao.MarketAnalyticsDAO;
import com.mandi.dao.SettlementDAO;
import com.mandi.engine.BidPlacementResult;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.engine.Trade;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;
import com.mandi.model.Settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facade that orchestrates the trading day: live bid intake (screening +
 * persistence), the clearing phase, transactional settlement of every trade,
 * and publication of the day's clearing prices back into the analytics store.
 */
public final class MarketService {

    private final DoubleAuctionEngine engine;
    private final BidDAO bidDAO = new BidDAO();
    private final AuctionLotDAO lotDAO = new AuctionLotDAO();
    private final SettlementDAO settlementDAO = new SettlementDAO();
    private final MarketAnalyticsDAO analyticsDAO = new MarketAnalyticsDAO();

    public MarketService(DoubleAuctionEngine engine) {
        this.engine = engine;
    }

    /**
     * Screens a live bid (MSP floor + anomaly detection) and persists the
     * outcome - accepted or rejected - to the Bids audit table. The generated
     * bid id is attached to the in-memory bid so trades can reference it.
     */
    public BidPlacementResult submitLiveBid(Bid bid) {
        BidPlacementResult result = engine.placeBid(bid);
        boolean anomaly = result.status() == BidStatus.REJECTED_ANOMALY;
        bidDAO.insert(bid, result.status(), anomaly);
        return result;
    }

    /** Publishes the latest 7-day rolling averages (window functions) to the engine. */
    public void publishRollingAverages() {
        engine.publishRollingAverages(analyticsDAO.getLatest7DayRollingAveragePerCrop());
    }

    public Map<Integer, BigDecimal> latest7DayRollingAverages() {
        return analyticsDAO.getLatest7DayRollingAveragePerCrop();
    }

    /** Runs the double-auction clearing phase across every registered crop. */
    public List<Trade> runClearingPhase() {
        return engine.clearAllMarkets();
    }

    /**
     * Settles one trade transactionally: moves the lot OPEN -&gt; MATCHED and
     * hands the trade to SettlementDAO.processSettlement (SERIALIZABLE +
     * FOR UPDATE row locks + escrow verification).
     */
    public Settlement settleTrade(Trade trade) {
        lotDAO.markMatched(trade.lotId());
        Settlement settlement = Settlement.newSettlement(trade.lotId(), trade.bidId(),
                trade.buyerId(), trade.farmerId(), trade.cropId(),
                trade.clearingPricePerQuintal(), trade.quantityQuintals());
        return settlementDAO.processSettlement(settlement);
    }

    /** Upserts today's volume-weighted average clearing price per crop into Price_History. */
    public void recordDailyClearingPrices(List<Trade> trades) {
        Map<Integer, List<Trade>> tradesByCrop = new HashMap<>();
        for (Trade trade : trades) {
            tradesByCrop.computeIfAbsent(trade.cropId(), id -> new ArrayList<>()).add(trade);
        }
        LocalDate today = LocalDate.now();
        for (Map.Entry<Integer, List<Trade>> entry : tradesByCrop.entrySet()) {
            BigDecimal valueSum = BigDecimal.ZERO;
            BigDecimal quantitySum = BigDecimal.ZERO;
            for (Trade trade : entry.getValue()) {
                valueSum = valueSum.add(trade.clearingPricePerQuintal().multiply(trade.quantityQuintals()));
                quantitySum = quantitySum.add(trade.quantityQuintals());
            }
            BigDecimal vwap = valueSum.divide(quantitySum, 2, RoundingMode.HALF_UP);
            analyticsDAO.upsertDailyAverage(entry.getKey(), today, vwap);
        }
    }

    public List<MarketAnalyticsDAO.RollingAverage> rolling7DayTrends() {
        return analyticsDAO.getRolling7DayAverages();
    }

    public List<Settlement> allSettlements() {
        return settlementDAO.findAll();
    }

    public List<BidDAO.BidRow> allBidRows() {
        return bidDAO.findAllRows();
    }

    public DoubleAuctionEngine engine() {
        return engine;
    }
}
