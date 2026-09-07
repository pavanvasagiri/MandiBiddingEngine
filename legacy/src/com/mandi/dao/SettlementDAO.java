package com.mandi.dao;

import com.mandi.dto.TradeSummaryDTO;
import com.mandi.engine.DoubleAuctionEngine.MatchResult;
import com.mandi.util.DBConnection;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Persistence is encapsulated in the stored procedures from sql/mandi_schema.sql. */
public final class SettlementDAO {
    public boolean processSettlement(MatchResult match) {
        try (Connection connection = DBConnection.getConnection();
             CallableStatement statement = connection.prepareCall("{CALL sp_settle_trade(?, ?, ?, ?, ?)}")) {
            statement.setString(1, match.lotId());
            statement.setString(2, match.buyerId());
            statement.setString(3, match.farmerId());
            statement.setBigDecimal(4, match.clearingPricePerQuintal());
            statement.setBigDecimal(5, match.matchedQuantityQuintals());
            statement.execute();
            return true;
        } catch (SQLException | IllegalStateException exception) {
            System.err.println("[SETTLEMENT FAILED] " + exception.getMessage());
            return false;
        }
    }

    public List<TradeSummaryDTO> listSettlements() throws SQLException {
        List<TradeSummaryDTO> settlements = new ArrayList<>();
        try (Connection connection = DBConnection.getConnection();
             CallableStatement statement = connection.prepareCall("{CALL sp_list_settlements()}");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Timestamp timestamp = result.getTimestamp("settled_at");
                settlements.add(new TradeSummaryDTO(
                    result.getString("lot_id"), result.getString("winning_buyer_id"), result.getString("farmer_id"),
                    result.getBigDecimal("gross_total"), result.getBigDecimal("mandi_tax_deducted"), result.getBigDecimal("farmer_net_payout"),
                    timestamp == null ? null : timestamp.toLocalDateTime()));
            }
        }
        return settlements;
    }
}
