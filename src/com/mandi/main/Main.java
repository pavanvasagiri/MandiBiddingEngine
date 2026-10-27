package com.mandi.main;

import com.mandi.dao.AuctionLotDAO;
import com.mandi.dao.BidDAO;
import com.mandi.dao.BuyerDAO;
import com.mandi.dao.DatabaseConnection;
import com.mandi.dao.DatabaseInitializer;
import com.mandi.dao.MarketAnalyticsDAO;
import com.mandi.dao.SettlementDAO;
import com.mandi.engine.BidPlacementResult;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.engine.Trade;
import com.mandi.model.AuctionLot;
import com.mandi.model.Ask;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Farmer;
import com.mandi.model.Settlement;
import com.mandi.service.CsvExporter;
import com.mandi.service.MarketService;
import com.mandi.service.SeedDataService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live execution simulation: bootstraps the mandi_db schema, seeds farmers /
 * buyers / crops-with-MSP / price history, hammers the double-auction engine
 * with concurrent bids from a 10-thread ExecutorService, clears the markets,
 * settles every trade transactionally (SERIALIZABLE + FOR UPDATE), prints
 * payouts, mandi tax and 7-day rolling price trends, and exports
 * historical_mandi_bids.csv.
 */
public final class Main {

    private static final Path CONFIG_FILE = Path.of("config", "db.properties");
    private static final String CSV_FILE_NAME = "historical_mandi_bids.csv";
    private static final int BIDDING_THREADS = 10;
    private static final int TOTAL_BIDS = 60;
    private static final int HISTORY_DAYS = 10;

    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "server".equalsIgnoreCase(args[0])) {
            // "server [port]" mode: run the mandi as a local web application
            // (dashboard at http://localhost:PORT) instead of the scripted simulation.
            com.mandi.server.MandiServerMain.main(args.length > 1 ? new String[] {args[1]} : new String[0]);
            return;
        }
        System.out.println("==================================================================");
        System.out.println("  AGRI-MARKET PRICE FORECASTING & MANDI BIDDING ENGINE");
        System.out.println("  Java 17+ | JDBC | MySQL | Double-Auction | ExecutorService");
        System.out.println("==================================================================");

        DatabaseConnection database = DatabaseConnection.init(DatabaseConnection.loadConfig(CONFIG_FILE));
        System.out.printf("MySQL connection pool ready (%d connections): %s%n%n", database.poolSize(), database.jdbcUrl());
        try {
            DatabaseInitializer initializer = new DatabaseInitializer();
            if (Boolean.parseBoolean(database.configValue("db.reset"))) {
                System.out.println("[BOOTSTRAP] db.reset=true -> recreating schema mandi_db ...");
                initializer.recreateSchema();
            }
            initializer.createTables();

            // -- Step 1: seed reference data and price history ----------------
            SeedDataService seedService = new SeedDataService();
            SeedDataService.SeedResult seed = seedService.seedReferenceData();
            seedService.seedPriceHistory(seed.crops(), HISTORY_DAYS);
            printSeededSummary(seed);

            // -- Step 2: wire engine registries, publish analytics, load asks -
            MarketService market = new MarketService(new DoubleAuctionEngine());
            market.engine().loadCrops(seed.crops());
            market.engine().loadBuyers(seed.buyers());
            market.engine().loadFarmers(seed.farmers());
            market.publishRollingAverages();
            for (AuctionLot lot : seed.lots()) {
                market.engine().placeAsk(new Ask(lot.getLotId(), lot.getCropId(), lot.getFarmerId(),
                        lot.getReservePricePerQuintal(), lot.getQuantityQuintals()));
            }

            // -- Step 3: 10-thread concurrent live bidding --------------------
            List<Bid> scriptedBids = buildBidScript(seed, market.latest7DayRollingAverages());
            Map<BidStatus, Integer> bidOutcomes = simulateConcurrentBidding(market, scriptedBids,
                    cropNames(seed.crops()), buyerNames(seed.buyers()));
            System.out.println("\n  Bid screening outcomes: " + bidOutcomes);
            printOrderBookDepth(market.engine());

            // -- Step 4: double-auction clearing phase ------------------------
            List<Trade> trades = market.runClearingPhase();
            printTrades(trades);

            // -- Step 5: transactional settlement of every trade --------------
            List<Settlement> settlements = new ArrayList<>();
            int settlementFailures = 0;
            for (Trade trade : trades) {
                try {
                    settlements.add(market.settleTrade(trade));
                } catch (RuntimeException settlementFailure) {
                    settlementFailures++;
                    System.err.printf("  [SETTLEMENT FAILED] lot %d: %s (transaction rolled back)%n",
                            trade.lotId(), settlementFailure.getMessage());
                }
            }
            market.recordDailyClearingPrices(trades); // today's VWAP joins the history

            printSettlementSummaries(settlements, settlementFailures);
            printRollingTrends(market);
            printVerificationSummary();
            exportCsv(market, settlements, seed);

            System.out.println("\nSimulation completed successfully.");
        } finally {
            database.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Bid script + concurrent submission
    // ------------------------------------------------------------------

    /**
     * Deterministic bid script (seeded RNG) over the seeded crops/buyers.
     * Bids #5, #17 and #35 are priced below MSP (must be auto-rejected); bids
     * #23 and #47 are priced at 160% of the crop's 7-day rolling average
     * (must be flagged as price manipulation); the rest sit between MSP +2%
     * and MSP +18%.
     */
    private static List<Bid> buildBidScript(SeedDataService.SeedResult seed, Map<Integer, BigDecimal> rollingAverages) {
        Random random = new Random(42L);
        List<Bid> bids = new ArrayList<>();
        List<Crop> crops = seed.crops();
        List<Buyer> buyers = seed.buyers();
        for (int i = 0; i < TOTAL_BIDS; i++) {
            Crop crop = crops.get(i % crops.size());
            Buyer buyer = buyers.get(i % buyers.size());
            BigDecimal quantity = BigDecimal.valueOf(5 + random.nextInt(26)); // 5..30 quintals
            BigDecimal price;
            if (i == 5 || i == 17 || i == 35) {
                price = crop.getMspPricePerQuintal().subtract(BigDecimal.valueOf(120)); // below MSP
            } else if (i == 23 || i == 47) {
                BigDecimal rolling = rollingAverages.getOrDefault(crop.getCropId(), crop.getMspPricePerQuintal());
                price = rolling.multiply(new BigDecimal("1.60")).setScale(2, RoundingMode.HALF_UP); // manipulation
            } else {
                price = premiumOverMsp(crop.getMspPricePerQuintal(), 200 + random.nextInt(1601)); // MSP +2%..+18%
            }
            bids.add(new Bid(crop.getCropId(), buyer.getBuyerId(), price, quantity));
        }
        return bids;
    }

    /** Submits every scripted bid through a fixed pool of {@value #BIDDING_THREADS} buyer threads. */
    private static Map<BidStatus, Integer> simulateConcurrentBidding(MarketService market, List<Bid> scriptedBids,
                                                                     Map<Integer, String> crops, Map<Integer, String> buyers)
            throws InterruptedException, ExecutionException {
        System.out.println("------------------------------------------------------------------");
        System.out.printf("LIVE BIDDING - %d bids submitted concurrently on %d pooled threads%n",
                scriptedBids.size(), BIDDING_THREADS);
        System.out.println("------------------------------------------------------------------");
        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService biddingPool = Executors.newFixedThreadPool(BIDDING_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "buyer-thread-" + threadCounter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        Map<BidStatus, Integer> outcomes = new EnumMap<>(BidStatus.class);
        try {
            List<Future<BidPlacementResult>> futures = new ArrayList<>();
            for (Bid bid : scriptedBids) {
                futures.add(biddingPool.submit(() -> {
                    BidPlacementResult result = market.submitLiveBid(bid);
                    synchronized (System.out) {
                        System.out.printf("  [%s] %-24s Rs.%10.2f x %5.2fq on %-18s -> %s%n",
                                Thread.currentThread().getName(),
                                buyers.getOrDefault(bid.getBuyerId(), "Buyer#" + bid.getBuyerId()),
                                bid.getBidPricePerQuintal(), bid.getQuantityQuintals(),
                                crops.getOrDefault(bid.getCropId(), "Crop#" + bid.getCropId()),
                                result.status() + (result.status().isAccepted() ? "" : " | " + result.reason()));
                    }
                    return result;
                }));
            }
            for (Future<BidPlacementResult> future : futures) {
                BidPlacementResult result = future.get();
                outcomes.merge(result.status(), 1, Integer::sum);
            }
        } finally {
            biddingPool.shutdown();
            if (!biddingPool.awaitTermination(60, TimeUnit.SECONDS)) {
                biddingPool.shutdownNow();
            }
        }
        return outcomes;
    }

    private static BigDecimal premiumOverMsp(BigDecimal msp, int basisPoints) {
        return msp.add(msp.multiply(BigDecimal.valueOf(basisPoints, 4))).setScale(2, RoundingMode.HALF_UP);
    }

    private static void printSeededSummary(SeedDataService.SeedResult seed) {
        System.out.println("\nSEEDED REFERENCE DATA");
        System.out.println("  Crops:");
        for (Crop crop : seed.crops()) System.out.printf("    [%d] %s%n", crop.getCropId(), crop);
        System.out.println("  Farmers:");
        for (Farmer farmer : seed.farmers()) System.out.printf("    [%d] %s (%s)%n", farmer.getFarmerId(), farmer.getName(), farmer.getVillage());
        System.out.println("  Buyers (escrow Rs." + SeedDataService.BUYER_ESCROW + " each):");
        for (Buyer buyer : seed.buyers()) System.out.printf("    [%d] %s%n", buyer.getBuyerId(), buyer.getName());
        System.out.printf("  Auction lots (farmer asks): %d%n", seed.lots().size());
    }

    private static void printOrderBookDepth(DoubleAuctionEngine engine) {
        System.out.println("\nORDER BOOK DEPTH after bidding:");
        for (Integer cropId : engine.registeredCropIds()) {
            System.out.printf("  Crop %-2d -> %2d open bids (max-heap) | %2d open asks (min-heap)%n",
                    cropId, engine.pendingBidCount(cropId), engine.pendingAskCount(cropId));
        }
    }

    private static void printTrades(List<Trade> trades) {
        System.out.println("\n==================================================================");
        System.out.println("CLEARING PHASE - equilibrium clearing price = (bid + ask) / 2");
        System.out.println("==================================================================");
        if (trades.isEmpty()) {
            System.out.println("  (no crossable orders)");
            return;
        }
        System.out.printf("  %-6s %-22s %-20s %12s %10s%n", "LOT", "BUYER", "FARMER", "CLEARING", "QTY(q)");
        for (Trade trade : trades) {
            System.out.printf("  #%-5d %-22s %-20s Rs.%9.2f %10.2f%n",
                    trade.lotId(), trade.buyerName(), trade.farmerName(),
                    trade.clearingPricePerQuintal(), trade.quantityQuintals());
        }
        System.out.printf("  Executed trades: %d%n", trades.size());
    }

    private static void printSettlementSummaries(List<Settlement> settlements, int failures) {
        System.out.println("\n==================================================================");
        System.out.println("SETTLEMENT PAYOUTS (buyer debited gross + 2% mandi tax)");
        System.out.println("==================================================================");
        if (settlements.isEmpty()) {
            System.out.println("  (nothing settled)");
        }
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal payoutTotal = BigDecimal.ZERO;
        for (Settlement settlement : settlements) {
            System.out.printf("  S#%-4d lot %-3d buyer %-2d farmer %-2d | gross Rs.%10.2f | mandi tax Rs.%8.2f | buyer debit Rs.%10.2f | farmer payout Rs.%10.2f%n",
                    settlement.getSettlementId(), settlement.getLotId(), settlement.getBuyerId(),
                    settlement.getFarmerId(), settlement.getGrossAmount(), settlement.getMandiTax(),
                    settlement.getBuyerDebit(), settlement.getFarmerPayout());
            grossTotal = grossTotal.add(settlement.getGrossAmount());
            taxTotal = taxTotal.add(settlement.getMandiTax());
            debitTotal = debitTotal.add(settlement.getBuyerDebit());
            payoutTotal = payoutTotal.add(settlement.getFarmerPayout());
        }
        System.out.printf("  %-70s Rs.%10.2f%n", "TOTAL gross traded value", grossTotal);
        System.out.printf("  %-70s Rs.%10.2f%n", "TOTAL mandi tax collected (2%)", taxTotal);
        System.out.printf("  %-70s Rs.%10.2f%n", "TOTAL buyer escrow debits", debitTotal);
        System.out.printf("  %-70s Rs.%10.2f%n", "TOTAL farmer payouts", payoutTotal);
        System.out.printf("  Settled transactions: %d (rolled back: %d)%n", settlements.size(), failures);
    }

    private static void printRollingTrends(MarketService market) {
        System.out.println("\n==================================================================");
        System.out.println("7-DAY ROLLING AVERAGE PRICE (AVG() OVER ... 6 PRECEDING AND CURRENT ROW)");
        System.out.println("==================================================================");
        Map<String, List<MarketAnalyticsDAO.RollingAverage>> seriesByCrop = new LinkedHashMap<>();
        for (MarketAnalyticsDAO.RollingAverage row : market.rolling7DayTrends()) {
            seriesByCrop.computeIfAbsent(row.cropName(), name -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<MarketAnalyticsDAO.RollingAverage>> entry : seriesByCrop.entrySet()) {
            List<MarketAnalyticsDAO.RollingAverage> rows = entry.getValue();
            System.out.println("  " + entry.getKey() + ":");
            for (int i = Math.max(0, rows.size() - 7); i < rows.size(); i++) {
                MarketAnalyticsDAO.RollingAverage row = rows.get(i);
                String marker = i == rows.size() - 1 ? "  <- latest (includes today's clearing VWAP)" : "";
                System.out.printf("    %s  daily Rs.%9.2f  rolling-7d Rs.%9.2f%s%n",
                        row.tradeDate(), row.avgPrice(), row.rolling7DayAvg(), marker);
            }
        }
    }

    private static void printVerificationSummary() {
        System.out.println("\n==================================================================");
        System.out.println("DATABASE VERIFICATION - committed state read back from MySQL");
        System.out.println("==================================================================");
        System.out.println("  Bids by status       : " + new BidDAO().countByStatus());
        System.out.println("  Lots by status       : " + new AuctionLotDAO().countByStatus());
        SettlementDAO settlementDAO = new SettlementDAO();
        System.out.println("  Settlements inserted : " + settlementDAO.countSettlements());
        System.out.printf("  Mandi tax collected  : Rs.%s%n", settlementDAO.totalMandiTaxCollected());
        BigDecimal escrowHeld = BigDecimal.ZERO;
        int buyerCount = 0;
        for (Buyer buyer : new BuyerDAO().findAll()) {
            escrowHeld = escrowHeld.add(buyer.getEscrowBalance());
            buyerCount++;
        }
        BigDecimal escrowOpened = SeedDataService.BUYER_ESCROW.multiply(BigDecimal.valueOf(buyerCount));
        System.out.printf("  Total escrow held    : Rs.%s (opened at Rs.%s)%n", escrowHeld, escrowOpened);
    }

    private static void exportCsv(MarketService market, List<Settlement> settlements, SeedDataService.SeedResult seed) {
        try {
            Path target = Path.of("").toAbsolutePath().resolve(CSV_FILE_NAME).normalize();
            new CsvExporter().export(market.allBidRows(), settlements, cropNames(seed.crops()), buyerNames(seed.buyers()), target);
            System.out.printf("%nCSV settlement log exported to %s%n", target);
        } catch (java.io.IOException exportFailure) {
            System.err.println("CSV export failed: " + exportFailure.getMessage());
        }
    }

    private static Map<Integer, String> cropNames(List<Crop> crops) {
        Map<Integer, String> names = new HashMap<>();
        for (Crop crop : crops) names.put(crop.getCropId(), crop.getCropName());
        return names;
    }

    private static Map<Integer, String> buyerNames(List<Buyer> buyers) {
        Map<Integer, String> names = new HashMap<>();
        for (Buyer buyer : buyers) names.put(buyer.getBuyerId(), buyer.getName());
        return names;
    }
}
