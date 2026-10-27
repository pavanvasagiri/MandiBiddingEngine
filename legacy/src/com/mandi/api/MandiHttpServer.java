package com.mandi.api;

import com.mandi.dao.SettlementDAO;
import com.mandi.dto.TradeSummaryDTO;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.model.Ask;
import com.mandi.model.Bid;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MandiHttpServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 65_536;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
    private final DoubleAuctionEngine engine;
    private final SettlementDAO settlements;
    private final byte[] apiKey;

    public MandiHttpServer(int port, DoubleAuctionEngine engine, SettlementDAO settlements, String apiKey) throws IOException {
        if (apiKey == null || apiKey.length() < 32) throw new IllegalArgumentException("MANDI_API_KEY must be at least 32 characters");
        this.engine = Objects.requireNonNull(engine);
        this.settlements = Objects.requireNonNull(settlements);
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/api/bids", secure(this::bid));
        server.createContext("/api/asks", secure(this::ask));
        server.createContext("/api/clear", secure(this::clear));
        server.createContext("/api/settlements", secure(this::listSettlements));
        server.createContext("/api/msp", secure(this::msp));
    }

    public void start() { server.start(); }

    @Override public void close() { server.stop(0); executor.shutdown(); }

    private HttpHandler secure(HttpHandler handler) {
        return exchange -> {
            if (!authorized(exchange)) { error(exchange, 401, "Unauthorized"); return; }
            try {
                handler.handle(exchange);
            } catch (IllegalArgumentException exception) {
                error(exchange, 400, exception.getMessage());
            } catch (Exception exception) {
                System.err.println("[API FAILED] " + exception.getMessage());
                error(exchange, 500, "Internal server error");
            }
        };
    }

    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-API-Key");
        return provided != null && MessageDigest.isEqual(apiKey, provided.getBytes(StandardCharsets.UTF_8));
    }

    private void bid(HttpExchange exchange) throws IOException {
        if (!jsonPost(exchange)) return;
        Map<String, String> request = json(body(exchange));
        Bid value = new Bid(request.getOrDefault("bidId", UUID.randomUUID().toString()), required(request, "lotId"),
            required(request, "buyerId"), decimal(request, "pricePerQuintal"), decimal(request, "quantityQuintals"), time(request.get("timestamp")));
        engine.submitBuyerBid(value, required(request, "cropId"));
        write(exchange, 201, "{\"accepted\":true,\"bidId\":\"" + esc(value.bidId()) + "\"}");
    }

    private void ask(HttpExchange exchange) throws IOException {
        if (!jsonPost(exchange)) return;
        Map<String, String> request = json(body(exchange));
        Ask value = new Ask(request.getOrDefault("askId", UUID.randomUUID().toString()), required(request, "lotId"),
            required(request, "farmerId"), decimal(request, "reservePricePerQuintal"), decimal(request, "quantityQuintals"), time(request.get("timestamp")));
        engine.submitFarmerAsk(value, required(request, "cropId"));
        write(exchange, 201, "{\"accepted\":true,\"askId\":\"" + esc(value.askId()) + "\"}");
    }

    private void clear(HttpExchange exchange) throws IOException {
        if (!jsonPost(exchange)) return;
        String cropId = required(json(body(exchange)), "cropId");
        List<TradeSummaryDTO> completed = new ArrayList<>();
        for (DoubleAuctionEngine.MatchResult match : engine.clearMarket(cropId, settlements::processSettlement)) {
            BigDecimal gross = match.clearingPricePerQuintal().multiply(match.matchedQuantityQuintals()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = gross.multiply(new BigDecimal(".02")).setScale(2, RoundingMode.HALF_UP);
            completed.add(new TradeSummaryDTO(match.lotId(), match.buyerId(), match.farmerId(), gross, tax,
                gross.subtract(tax).setScale(2, RoundingMode.HALF_UP), LocalDateTime.now()));
        }
        write(exchange, 200, array(completed));
    }

    private void listSettlements(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        try {
            write(exchange, 200, array(settlements.listSettlements()));
        } catch (Exception exception) {
            throw new IOException("Unable to retrieve settlements", exception);
        }
    }

    private void msp(HttpExchange exchange) throws IOException {
        if (!jsonPost(exchange)) return;
        Map<String, String> request = json(body(exchange));
        engine.registerMsp(required(request, "cropId"), decimal(request, "msp"));
        write(exchange, 200, "{\"updated\":true}");
    }

    private static boolean jsonPost(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return false;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            error(exchange, 415, "Content-Type must be application/json"); return false;
        }
        return true;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        error(exchange, 405, "Method not allowed");
        return false;
    }

    private static String body(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Content-Length");
        if (header != null && Long.parseLong(header) > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body exceeds 64 KiB");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body exceeds 64 KiB");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, String> json(String input) {
        String source = input.trim();
        if (source.length() < 2 || source.charAt(0) != '{' || source.charAt(source.length() - 1) != '}') throw new IllegalArgumentException("Expected JSON object");
        Map<String, String> fields = new HashMap<>();
        int position = 1;
        while (true) {
            position = space(source, position);
            if (position == source.length() - 1) return fields;
            Token key = string(source, position);
            position = space(source, key.next());
            if (position >= source.length() || source.charAt(position++) != ':') throw new IllegalArgumentException("Invalid JSON");
            position = space(source, position);
            Token value = position < source.length() && source.charAt(position) == '"' ? string(source, position) : scalar(source, position);
            if (fields.put(key.value(), value.value()) != null) throw new IllegalArgumentException("Duplicate JSON field: " + key.value());
            position = space(source, value.next());
            if (position == source.length() - 1) return fields;
            if (position >= source.length() || source.charAt(position++) != ',') throw new IllegalArgumentException("Invalid JSON");
        }
    }

    private static Token string(String source, int position) {
        if (position >= source.length() || source.charAt(position) != '"') throw new IllegalArgumentException("Expected JSON string");
        StringBuilder value = new StringBuilder();
        for (position++; position < source.length(); position++) {
            char character = source.charAt(position);
            if (character == '"') return new Token(value.toString(), position + 1);
            if (character < 0x20) throw new IllegalArgumentException("Invalid control character in JSON string");
            if (character != '\\') { value.append(character); continue; }
            if (++position >= source.length()) break;
            character = source.charAt(position);
            switch (character) {
                case '"', '\\', '/' -> value.append(character);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 >= source.length()) throw new IllegalArgumentException("Invalid Unicode escape");
                    try { value.append((char) Integer.parseInt(source.substring(position + 1, position + 5), 16)); }
                    catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid Unicode escape"); }
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Unsupported JSON escape");
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static Token scalar(String source, int position) {
        int end = position;
        while (end < source.length() && source.charAt(end) != ',' && source.charAt(end) != '}') end++;
        String value = source.substring(position, end).trim();
        if (value.isEmpty() || value.equals("null") || value.startsWith("{") || value.startsWith("[")) throw new IllegalArgumentException("Expected scalar JSON value");
        return new Token(value, end);
    }

    private static int space(String source, int position) { while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++; return position; }
    private static String required(Map<String, String> values, String key) { String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required field: " + key); return value; }
    private static BigDecimal decimal(Map<String, String> values, String key) { try { return new BigDecimal(required(values, key)); } catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid decimal field: " + key); } }
    private static LocalDateTime time(String value) { try { return value == null || value.isBlank() ? LocalDateTime.now() : LocalDateTime.parse(value); } catch (Exception exception) { throw new IllegalArgumentException("timestamp must be ISO-8601 local date-time"); } }

    private static String array(List<TradeSummaryDTO> values) {
        StringBuilder output = new StringBuilder("[");
        for (TradeSummaryDTO value : values) {
            if (output.length() > 1) output.append(',');
            output.append("{\"lotId\":\"").append(esc(value.lotId())).append("\",\"buyerId\":\"").append(esc(value.buyerId()))
                .append("\",\"farmerId\":\"").append(esc(value.farmerId())).append("\",\"grossAmount\":").append(value.grossAmount().toPlainString())
                .append(",\"mandiTax\":").append(value.mandiTax().toPlainString()).append(",\"netPayout\":").append(value.netPayout().toPlainString())
                .append(",\"settlementTimestamp\":").append(value.settlementTimestamp() == null ? "null" : "\"" + esc(value.settlementTimestamp().toString()) + "\"").append('}');
        }
        return output.append(']').toString();
    }

    private static void error(HttpExchange exchange, int status, String text) throws IOException { write(exchange, status, "{\"error\":\"" + esc(text) + "\"}"); }
    private static void write(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String esc(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\""); case '\\' -> escaped.append("\\\\"); case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f"); case '\n' -> escaped.append("\\n"); case '\r' -> escaped.append("\\r"); case '\t' -> escaped.append("\\t");
                default -> { if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character)); else escaped.append(character); }
            }
        }
        return escaped.toString();
    }

    private record Token(String value, int next) { }
}
