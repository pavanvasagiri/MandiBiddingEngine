package com.mandi.server;

import com.mandi.dao.AuctionLotDAO;
import com.mandi.dao.BuyerDAO;
import com.mandi.dao.CropDAO;
import com.mandi.dao.DatabaseConnection;
import com.mandi.dao.DatabaseInitializer;
import com.mandi.dao.FarmerDAO;
import com.mandi.dao.MarketAnalyticsDAO;
import com.mandi.dao.SettlementDAO;
import com.mandi.engine.BidPlacementResult;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.engine.Trade;
import com.mandi.model.Ask;
import com.mandi.model.AuctionLot;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;
import com.mandi.model.Buyer;
import com.mandi.model.Crop;
import com.mandi.model.Farmer;
import com.mandi.model.LotStatus;
import com.mandi.model.Settlement;
import com.mandi.service.CsvExporter;
import com.mandi.service.MarketService;
import com.mandi.service.SeedDataService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local HTTP application server for the mandi: an HTML trading dashboard at
 * {@code /} plus a JSON API (bids, asks, clearing, settlements, analytics,
 * CSV export, concurrent burst simulation). Built on the JDK's built-in
 * {@code com.sun.net.httpserver} - no web framework dependencies.
 *
 * <p>Thread model: requests are served by a 10-thread executor; the
 * {@link DoubleAuctionEngine} behind {@link MarketService} is fully
 * thread-safe, and every settlement is its own SERIALIZABLE JDBC transaction.
 */
public final class MandiHttpServer {

    private static final String JSON = "application/json; charset=utf-8";
    private static final int HISTORY_DAYS = 10;

    private final int port;
    private final SeedDataService seedService = new SeedDataService();
    private final DatabaseInitializer initializer = new DatabaseInitializer();
    private final CropDAO cropDAO = new CropDAO();
    private final FarmerDAO farmerDAO = new FarmerDAO();
    private final BuyerDAO buyerDAO = new BuyerDAO();
    private final AuctionLotDAO lotDAO = new AuctionLotDAO();
    private final SettlementDAO settlementDAO = new SettlementDAO();
    private volatile MarketService market;

    public MandiHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        market = bootstrapMarket(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleDashboard);
        server.createContext("/api/health", ex -> guard(ex, this::handleHealth));
        server.createContext("/api/crops", ex -> guard(ex, this::handleCrops));
        server.createContext("/api/book", ex -> guard(ex, this::handleBook));
        server.createContext("/api/bids", ex -> guard(ex, this::handleBid));
        server.createContext("/api/asks", ex -> guard(ex, this::handleAsk));
        server.createContext("/api/clear", ex -> guard(ex, this::handleClear));
        server.createContext("/api/settlements", ex -> guard(ex, this::handleSettlements));
        server.createContext("/api/trends", ex -> guard(ex, this::handleTrends));
        server.createContext("/api/export.csv", ex -> guard(ex, this::handleExport));
        server.createContext("/api/simulate/burst", ex -> guard(ex, this::handleBurst));
        server.createContext("/api/admin/reseed", ex -> guard(ex, this::handleReseed));
        AtomicInteger threadCounter = new AtomicInteger();
        server.setExecutor(Executors.newFixedThreadPool(10,
                task -> new Thread(task, "http-thread-" + threadCounter.incrementAndGet())));
        server.start();
        System.out.println("Mandi HTTP server listening on http://localhost:" + port + "  (dashboard at /)");
    }

    /**
     * Rebuilds a known market state: schema bootstrap, seed-if-empty, engine
     * registry wiring and loading all OPEN lots into the ask book.
     */
    private MarketService bootstrapMarket(boolean recreate) {
        if (recreate || Boolean.parseBoolean(DatabaseConnection.getInstance().configValue("db.reset"))) {
            System.out.println("[BOOTSTRAP] recreating mandi_db for a fresh market ...");
            initializer.recreateSchema();
        }
        initializer.createTables();
        MarketService service = new MarketService(new DoubleAuctionEngine());
        List<Crop> crops = cropDAO.findAll();
        List<Farmer> farmers;
        List<Buyer> buyers;
        List<AuctionLot> lots;
        if (crops.isEmpty()) {
            SeedDataService.SeedResult seed = seedService.seedReferenceData();
            seedService.seedPriceHistory(seed.crops(), HISTORY_DAYS);
            crops = seed.crops();
            farmers = seed.farmers();
            buyers = seed.buyers();
            lots = seed.lots();
            System.out.println("[BOOTSTRAP] seeded fresh market data (crops, farmers, buyers, lots, price history)");
        } else {
            farmers = farmerDAO.findAll();
            buyers = buyerDAO.findAll();
            lots = lotDAO.findAll();
            System.out.println("[BOOTSTRAP] existing market data found - reloading OPEN lots into the ask book");
        }
        service.engine().loadCrops(crops);
        service.engine().loadBuyers(buyers);
        service.engine().loadFarmers(farmers);
        service.publishRollingAverages();
        int asksLoaded = 0;
        for (AuctionLot lot : lots) {
            if (lot.getStatus() != LotStatus.OPEN) continue;
            BigDecimal remaining = lot.getQuantityQuintals().subtract(lot.getFilledQuantity());
            if (remaining.signum() > 0) {
                service.engine().placeAsk(new Ask(lot.getLotId(), lot.getCropId(), lot.getFarmerId(),
                        lot.getReservePricePerQuintal(), remaining));
                asksLoaded++;
            }
        }
        System.out.println("[BOOTSTRAP] ask book loaded with " + asksLoaded + " open lot(s)");
        return service;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleDashboard(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found. The dashboard is at /");
            return;
        }
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", new DashboardRenderer().render(market));
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        String databaseState;
        try (Connection connection = DatabaseConnection.getInstance().getConnection()) {
            databaseState = connection.isValid(3) ? "UP" : "DEGRADED";
        } catch (SQLException e) {
            databaseState = "DOWN: " + e.getMessage();
        }
        send(ex, 200, JSON, "{\"status\":\"UP\",\"database\":" + JsonUtil.str(databaseState) + ",\"port\":" + port + "}");
    }

    private void handleCrops(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        Map<Integer, BigDecimal> rolling = market.latest7DayRollingAverages();
        StringBuilder json = new StringBuilder("[");
        for (Crop crop : cropDAO.findAll()) {
            json.append("{\"cropId\":").append(crop.getCropId())
                .append(",\"cropName\":").append(JsonUtil.str(crop.getCropName()))
                .append(",\"mspPricePerQuintal\":").append(crop.getMspPricePerQuintal())
                .append(",\"rolling7dAvg\":").append(rolling.get(crop.getCropId())).append("},");
        }
        trimTrailingComma(json);
        send(ex, 200, JSON, json.append("]").toString());
    }

    private void handleBook(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        DoubleAuctionEngine engine = market.engine();
        StringBuilder json = new StringBuilder("[");
        for (Integer cropId : engine.registeredCropIds()) {
            json.append("{\"cropId\":").append(cropId)
                .append(",\"openBids\":").append(engine.pendingBidCount(cropId))
                .append(",\"openAsks\":").append(engine.pendingAskCount(cropId)).append("},");
        }
        trimTrailingComma(json);
        send(ex, 200, JSON, json.append("]").toString());
    }

    private void handleBid(HttpExchange ex) throws IOException {
        if (!isPost(ex)) {
            send(ex, 405, JSON, errorJson("Use POST"));
            return;
        }
        Map<String, String> body = JsonUtil.parseFlatObject(readBody(ex));
        Bid bid = new Bid(intOf(body, "cropId"), intOf(body, "buyerId"),
                decimalOf(body, "price"), decimalOf(body, "quantity"));
        BidPlacementResult result = market.submitLiveBid(bid); // screens (MSP + anomaly) and persists
        String json = "{\"bidId\":" + bid.getBidId()
                + ",\"status\":\"" + result.status() + "\""
                + ",\"reason\":" + JsonUtil.str(result.reason())
                + ",\"referencePrice\":" + result.referencePrice() + "}";
        send(ex, 200, JSON, json);
    }

    private void handleAsk(HttpExchange ex) throws IOException {
        if (!isPost(ex)) {
            send(ex, 405, JSON, errorJson("Use POST"));
            return;
        }
        Map<String, String> body = JsonUtil.parseFlatObject(readBody(ex));
        AuctionLot lot = new AuctionLot(intOf(body, "cropId"), intOf(body, "farmerId"),
                decimalOf(body, "quantity"), decimalOf(body, "reservePrice"));
        lotDAO.insert(lot);
        market.engine().placeAsk(new Ask(lot.getLotId(), lot.getCropId(), lot.getFarmerId(),
                lot.getReservePricePerQuintal(), lot.getQuantityQuintals()));
        send(ex, 200, JSON, "{\"lotId\":" + lot.getLotId()
                + ",\"status\":\"OPEN\""
                + ",\"quantityQuintals\":" + lot.getQuantityQuintals()
                + ",\"reservePricePerQuintal\":" + lot.getReservePricePerQuintal() + "}");
    }

    private void handleClear(HttpExchange ex) throws IOException {
        if (!isPost(ex)) {
            send(ex, 405, JSON, errorJson("Use POST"));
            return;
        }
        List<Trade> trades = market.runClearingPhase();
        List<Settlement> settlements = new ArrayList<>();
        int rolledBack = 0;
        for (Trade trade : trades) {
            try {
                settlements.add(market.settleTrade(trade));
            } catch (RuntimeException failure) {
                rolledBack++;
                System.err.println("[HTTP /api/clear] settlement rolled back for lot "
                        + trade.lotId() + ": " + failure.getMessage());
            }
        }
        market.recordDailyClearingPrices(trades); // today's VWAP joins the price history

        StringBuilder json = new StringBuilder("{\"executedTrades\":").append(trades.size())
                .append(",\"settled\":").append(settlements.size())
                .append(",\"rolledBack\":").append(rolledBack)
                .append(",\"settlements\":[");
        for (Settlement s : settlements) {
            json.append("{\"settlementId\":").append(s.getSettlementId())
                .append(",\"lotId\":").append(s.getLotId())
                .append(",\"bidId\":").append(s.getBidId())
                .append(",\"buyerId\":").append(s.getBuyerId())
                .append(",\"farmerId\":").append(s.getFarmerId())
                .append(",\"clearingPricePerQuintal\":").append(s.getClearingPricePerQuintal())
                .append(",\"quantityQuintals\":").append(s.getQuantityQuintals())
                .append(",\"grossAmount\":").append(s.getGrossAmount())
                .append(",\"mandiTax\":").append(s.getMandiTax())
                .append(",\"buyerDebit\":").append(s.getBuyerDebit())
                .append(",\"farmerPayout\":").append(s.getFarmerPayout()).append("},");
        }
        trimTrailingComma(json);
        send(ex, 200, JSON, json.append("]}").toString());
    }

    private void handleSettlements(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        List<Settlement> settlements = settlementDAO.findAll();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal payout = BigDecimal.ZERO;
        for (Settlement s : settlements) {
            gross = gross.add(s.getGrossAmount());
            tax = tax.add(s.getMandiTax());
            payout = payout.add(s.getFarmerPayout());
        }
        StringBuilder json = new StringBuilder("{\"count\":").append(settlements.size())
                .append(",\"grossAmount\":").append(gross)
                .append(",\"mandiTax\":").append(tax)
                .append(",\"farmerPayout\":").append(payout)
                .append(",\"settlements\":[");
        for (Settlement s : settlements) {
            json.append("{\"settlementId\":").append(s.getSettlementId())
                .append(",\"lotId\":").append(s.getLotId())
                .append(",\"bidId\":").append(s.getBidId())
                .append(",\"buyerId\":").append(s.getBuyerId())
                .append(",\"farmerId\":").append(s.getFarmerId())
                .append(",\"cropId\":").append(s.getCropId())
                .append(",\"clearingPricePerQuintal\":").append(s.getClearingPricePerQuintal())
                .append(",\"quantityQuintals\":").append(s.getQuantityQuintals())
                .append(",\"grossAmount\":").append(s.getGrossAmount())
                .append(",\"mandiTax\":").append(s.getMandiTax())
                .append(",\"buyerDebit\":").append(s.getBuyerDebit())
                .append(",\"farmerPayout\":").append(s.getFarmerPayout()).append("},");
        }
        trimTrailingComma(json);
        send(ex, 200, JSON, json.append("]}").toString());
    }

    private void handleTrends(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        StringBuilder json = new StringBuilder("[");
        for (MarketAnalyticsDAO.RollingAverage row : market.rolling7DayTrends()) {
            json.append("{\"cropId\":").append(row.cropId())
                .append(",\"cropName\":").append(JsonUtil.str(row.cropName()))
                .append(",\"tradeDate\":\"").append(row.tradeDate()).append('"')
                .append(",\"avgPricePerQuintal\":").append(row.avgPrice())
                .append(",\"rolling7dAvg\":").append(row.rolling7DayAvg()).append("},");
        }
        trimTrailingComma(json);
        send(ex, 200, JSON, json.append("]").toString());
    }

    private void handleExport(HttpExchange ex) throws IOException {
        if (!isGet(ex)) {
            send(ex, 405, JSON, errorJson("Use GET"));
            return;
        }
        Map<Integer, String> cropNames = new HashMap<>();
        for (Crop crop : cropDAO.findAll()) cropNames.put(crop.getCropId(), crop.getCropName());
        Map<Integer, String> buyerNames = new HashMap<>();
        for (Buyer buyer : buyerDAO.findAll()) buyerNames.put(buyer.getBuyerId(), buyer.getName());
        Path temp = Files.createTempFile("mandi-export", ".csv");
        try {
            new CsvExporter().export(market.allBidRows(), market.allSettlements(), cropNames, buyerNames, temp);
            byte[] payload = Files.readAllBytes(temp);
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=historical_mandi_bids.csv");
            send(ex, 200, "text/csv; charset=utf-8", payload);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void handleBurst(HttpExchange ex) throws IOException {
        if (!isPost(ex)) {
            send(ex, 405, JSON, errorJson("Use POST"));
            return;
        }
        String countParam = queryParams(ex).get("count");
        if (countParam == null) countParam = JsonUtil.parseFlatObject(readBody(ex)).get("count");
        int count = 40;
        if (countParam != null && !countParam.isBlank()) count = Integer.parseInt(countParam.trim());
        count = Math.max(1, Math.min(count, 500));

        List<Crop> crops = cropDAO.findAll();
        List<Buyer> buyers = buyerDAO.findAll();
        Random random = new Random();
        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10,
                task -> new Thread(task, "burst-thread-" + threadCounter.incrementAndGet()));
        Map<BidStatus, Integer> outcomes = new EnumMap<>(BidStatus.class);
        long startNanos = System.nanoTime();
        try {
            List<Future<BidPlacementResult>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Crop crop = crops.get(random.nextInt(crops.size()));
                Buyer buyer = buyers.get(random.nextInt(buyers.size()));
                BigDecimal price = premiumOverMsp(crop.getMspPricePerQuintal(), 200 + random.nextInt(1601));
                BigDecimal quantity = BigDecimal.valueOf(5 + random.nextInt(26));
                futures.add(pool.submit(() -> market.submitLiveBid(
                        new Bid(crop.getCropId(), buyer.getBuyerId(), price, quantity))));
            }
            for (Future<BidPlacementResult> future : futures) {
                outcomes.merge(future.get().status(), 1, Integer::sum);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Burst bidding interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Burst bid failed: " + e.getCause(), e);
        } finally {
            pool.shutdown();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        StringBuilder outcomesJson = new StringBuilder("{");
        for (Map.Entry<BidStatus, Integer> outcome : outcomes.entrySet()) {
            outcomesJson.append('"').append(outcome.getKey()).append("\":").append(outcome.getValue()).append(',');
        }
        trimTrailingComma(outcomesJson);
        outcomesJson.append('}');
        send(ex, 200, JSON, "{\"submitted\":" + count
                + ",\"outcomes\":" + outcomesJson
                + ",\"elapsedMs\":" + elapsedMs + "}");
    }

    private void handleReseed(HttpExchange ex) throws IOException {
        if (!isPost(ex)) {
            send(ex, 405, JSON, errorJson("Use POST"));
            return;
        }
        market = bootstrapMarket(true); // volatile swap; in-flight requests finish on the old instance
        send(ex, 200, JSON, "{\"status\":\"reseeded\",\"message\":\"Fresh market ready\"}");
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private void guard(HttpExchange exchange, HandlerDelegate delegate) {
        try {
            delegate.handle(exchange);
        } catch (IllegalArgumentException badRequest) {
            respondQuietly(exchange, 400, errorJson(badRequest.getMessage()));
        } catch (Exception failure) {
            System.err.println("[HTTP] " + exchange.getRequestURI() + " failed: " + failure);
            respondQuietly(exchange, 500, errorJson(
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    @FunctionalInterface
    private interface HandlerDelegate {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static void respondQuietly(HttpExchange exchange, int code, String body) {
        try {
            send(exchange, code, JSON, body);
        } catch (IOException ignored) {
            // client disconnected - nothing further to do
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":" + JsonUtil.str(message) + "}";
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        send(exchange, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, payload.length == 0 ? -1 : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static boolean isGet(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static boolean isPost(HttpExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static void trimTrailingComma(StringBuilder json) {
        int end = json.length() - 1;
        if (end >= 0 && json.charAt(end) == ',') json.setLength(end);
    }

    private static int intOf(Map<String, String> body, String field) {
        String value = body.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing field: " + field);
        return Integer.parseInt(value.trim());
    }

    private static BigDecimal decimalOf(Map<String, String> body, String field) {
        String value = body.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing field: " + field);
        return new BigDecimal(value.trim());
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static BigDecimal premiumOverMsp(BigDecimal msp, int basisPoints) {
        return msp.add(msp.multiply(BigDecimal.valueOf(basisPoints, 4))).setScale(2, RoundingMode.HALF_UP);
    }
}
