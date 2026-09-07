package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.Bid;
import com.mandi.model.BidStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the live bid stream. Every submitted bid - accepted or
 * rejected - is written here, giving the mandi a complete audit trail that
 * feeds the CSV exporter.
 */
public final class BidDAO {

    /** A bid plus its screening outcome as stored in the database. */
    public record BidRow(Bid bid, BidStatus status, boolean anomalyFlag) { }

    /** Inserts a bid and returns the generated bid_id (also set on the object). */
    public int insert(Bid bid, BidStatus status, boolean anomalyFlag) {
        String sql = """
                INSERT INTO Bids (crop_id, buyer_id, bid_price_per_quintal, quantity_quintals, status, anomaly_flag, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, bid.getCropId());
                statement.setInt(2, bid.getBuyerId());
                statement.setBigDecimal(3, bid.getBidPricePerQuintal());
                statement.setBigDecimal(4, bid.getQuantityQuintals());
                statement.setString(5, status.name());
                statement.setInt(6, anomalyFlag ? 1 : 0);
                statement.setTimestamp(7, java.sql.Timestamp.from(bid.getPlacedAt()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) bid.setBidId(keys.getInt(1));
                }
            }
            if (bid.getBidId() == null) {
                throw new MandiDataAccessException("Insert returned no generated key for bid");
            }
            return bid.getBidId();
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to insert bid", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public List<BidRow> findAllRows() {
        String sql = """
                SELECT bid_id, crop_id, buyer_id, bid_price_per_quintal, quantity_quintals, status, anomaly_flag, placed_at
                FROM Bids ORDER BY bid_id
                """;
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<BidRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Bid bid = new Bid(resultSet.getInt("crop_id"), resultSet.getInt("buyer_id"),
                            resultSet.getBigDecimal("bid_price_per_quintal"),
                            resultSet.getBigDecimal("quantity_quintals"),
                            resultSet.getTimestamp("placed_at").toInstant());
                    bid.setBidId(resultSet.getInt("bid_id"));
                    rows.add(new BidRow(bid,
                            BidStatus.valueOf(resultSet.getString("status")),
                            resultSet.getInt("anomaly_flag") == 1));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load bids", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Committed-state summary used by the end-of-run verification report. */
    public Map<String, Integer> countByStatus() {
        String sql = "SELECT status, COUNT(*) AS total FROM Bids GROUP BY status ORDER BY status";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                while (resultSet.next()) {
                    counts.put(resultSet.getString("status"), resultSet.getInt("total"));
                }
                return counts;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to count bids by status", e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
