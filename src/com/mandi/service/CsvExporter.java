package com.mandi.service;

import com.mandi.dao.BidDAO;
import com.mandi.model.BidStatus;
import com.mandi.model.Settlement;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exports the completed auction settlement log (every bid with its screening
 * outcome plus, where matched, its settlement financials) to a CSV file -
 * by default {@code historical_mandi_bids.csv} in the project root.
 */
public final class CsvExporter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String[] HEADER = {
            "bid_id", "placed_at", "buyer_id", "buyer_name", "crop_id", "crop_name",
            "bid_price_per_quintal", "quantity_quintals", "bid_status", "anomaly_flag",
            "settlement_id", "lot_id", "clearing_price_per_quintal", "settled_quantity_quintals",
            "gross_amount", "mandi_tax", "buyer_debit", "farmer_payout"
    };

    /**
     * Writes one row per settlement plus one row per unmatched/rejected bid,
     * ordered by bid id. Returns the path written.
     */
    public Path export(List<BidDAO.BidRow> bidRows, List<Settlement> settlements,
                       Map<Integer, String> cropNames, Map<Integer, String> buyerNames, Path target) throws IOException {
        Map<Integer, List<Settlement>> settlementsByBid = new java.util.HashMap<>();
        for (Settlement settlement : settlements) {
            settlementsByBid.computeIfAbsent(settlement.getBidId(), id -> new ArrayList<>()).add(settlement);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", HEADER));
            writer.newLine();
            for (BidDAO.BidRow row : bidRows) {
                var bid = row.bid();
                List<Settlement> bidSettlements = settlementsByBid.getOrDefault(bid.getBidId(), List.of());
                if (bidSettlements.isEmpty()) {
                    writer.write(toCsvRow(row, null, cropNames, buyerNames));
                } else {
                    for (Settlement settlement : bidSettlements) {
                        writer.write(toCsvRow(row, settlement, cropNames, buyerNames));
                    }
                }
            }
        }
        return target;
    }

    private static String toCsvRow(BidDAO.BidRow row, Settlement settlement,
                                   Map<Integer, String> cropNames, Map<Integer, String> buyerNames) {
        var bid = row.bid();
        StringBuilder line = new StringBuilder();
        append(line, bid.getBidId() == null ? "" : bid.getBidId().toString());
        append(line, TIMESTAMP_FORMAT.format(bid.getPlacedAt()));
        append(line, String.valueOf(bid.getBuyerId()));
        append(line, buyerNames.getOrDefault(bid.getBuyerId(), "Buyer#" + bid.getBuyerId()));
        append(line, String.valueOf(bid.getCropId()));
        append(line, cropNames.getOrDefault(bid.getCropId(), "Crop#" + bid.getCropId()));
        append(line, bid.getBidPricePerQuintal().toPlainString());
        append(line, bid.getQuantityQuintals().toPlainString());
        append(line, row.status().name());
        append(line, row.anomalyFlag() ? "1" : "0");
        append(line, settlement == null || settlement.getSettlementId() == null ? "" : settlement.getSettlementId().toString());
        append(line, settlement == null ? "" : String.valueOf(settlement.getLotId()));
        append(line, settlement == null ? "" : settlement.getClearingPricePerQuintal().toPlainString());
        append(line, settlement == null ? "" : settlement.getQuantityQuintals().toPlainString());
        append(line, settlement == null ? "" : settlement.getGrossAmount().toPlainString());
        append(line, settlement == null ? "" : settlement.getMandiTax().toPlainString());
        append(line, settlement == null ? "" : settlement.getBuyerDebit().toPlainString());
        append(line, settlement == null ? "" : settlement.getFarmerPayout().toPlainString());
        line.setLength(line.length() - 1); // drop trailing comma
        line.append('\n');
        return line.toString();
    }

    /** Minimal RFC-4180 escaping: quotes doubled, fields with commas/quotes/newlines wrapped. */
    private static void append(StringBuilder line, String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            line.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            line.append(value);
        }
        line.append(',');
    }
}
