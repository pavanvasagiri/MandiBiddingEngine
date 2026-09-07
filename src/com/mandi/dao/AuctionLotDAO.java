package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.AuctionLot;
import com.mandi.model.LotStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence for {@link AuctionLot} (farmer consignments / asks). */
public final class AuctionLotDAO {

    /** Inserts a lot and returns the generated lot_id (also set on the object). */
    public int insert(AuctionLot lot) {
        String sql = """
                INSERT INTO Auction_Lots (crop_id, farmer_id, quantity_quintals, reserve_price_per_quintal, status)
                VALUES (?, ?, ?, ?, ?)
                """;
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, lot.getCropId());
                statement.setInt(2, lot.getFarmerId());
                statement.setBigDecimal(3, lot.getQuantityQuintals());
                statement.setBigDecimal(4, lot.getReservePricePerQuintal());
                statement.setString(5, lot.getStatus().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) lot.setLotId(keys.getInt(1));
                }
            }
            if (lot.getLotId() == null) {
                throw new MandiDataAccessException("Insert returned no generated key for auction lot");
            }
            return lot.getLotId();
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to insert auction lot", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Moves an OPEN lot to MATCHED once the engine has produced a trade for it. */
    public int markMatched(int lotId) {
        String sql = "UPDATE Auction_Lots SET status = 'MATCHED' WHERE lot_id = ? AND status = 'OPEN'";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, lotId);
                return statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to mark lot " + lotId + " as MATCHED", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public List<AuctionLot> findAll() {
        String sql = """
                SELECT lot_id, crop_id, farmer_id, quantity_quintals, reserve_price_per_quintal,
                       filled_quantity, status, created_at
                FROM Auction_Lots ORDER BY lot_id
                """;
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<AuctionLot> lots = new ArrayList<>();
                while (resultSet.next()) {
                    AuctionLot lot = new AuctionLot(resultSet.getInt("crop_id"), resultSet.getInt("farmer_id"),
                            resultSet.getBigDecimal("quantity_quintals"),
                            resultSet.getBigDecimal("reserve_price_per_quintal"),
                            resultSet.getTimestamp("created_at").toInstant());
                    lot.setLotId(resultSet.getInt("lot_id"));
                    lot.setFilledQuantity(resultSet.getBigDecimal("filled_quantity"));
                    lot.setStatus(LotStatus.valueOf(resultSet.getString("status")));
                    lots.add(lot);
                }
                return lots;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load auction lots", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Committed-state summary used by the end-of-run verification report. */
    public Map<String, Integer> countByStatus() {
        String sql = "SELECT status, COUNT(*) AS total FROM Auction_Lots GROUP BY status ORDER BY status";
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
            throw new MandiDataAccessException("Failed to count lots by status", e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
