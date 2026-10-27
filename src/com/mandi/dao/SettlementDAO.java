package com.mandi.dao;

import com.mandi.exception.LotNotSettleableException;
import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.LotStatus;
import com.mandi.model.Settlement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Settlement persistence with full ACID guarantees.
 *
 * <p>{@link #processSettlement} runs inside an explicit JDBC transaction at
 * {@link Connection#TRANSACTION_SERIALIZABLE} isolation and takes pessimistic
 * row-level locks:
 * <ol>
 *   <li>{@code SELECT status ... FROM Auction_Lots WHERE lot_id = ? FOR UPDATE}</li>
 *   <li>{@code SELECT escrow_balance FROM Buyers WHERE buyer_id = ? FOR UPDATE}</li>
 * </ol>
 * It then verifies the escrow covers the total debit, debits the buyer,
 * accumulates the lot fill (flipping it to SETTLED once fully consumed),
 * inserts the Settlement row and commits - or rolls everything back on any
 * failure. Concurrent settlements of the same lot or buyer serialize safely
 * on the row locks.
 */
public final class SettlementDAO {

    private static final String LOCK_LOT_SQL =
            "SELECT status, quantity_quintals, filled_quantity FROM Auction_Lots WHERE lot_id = ? FOR UPDATE";
    private static final String LOCK_BUYER_SQL =
            "SELECT escrow_balance FROM Buyers WHERE buyer_id = ? FOR UPDATE";
    private static final String DEBIT_BUYER_SQL =
            "UPDATE Buyers SET escrow_balance = escrow_balance - ? WHERE buyer_id = ?";
    // New fill and status are computed in Java under the row lock (MySQL evaluates
    // SET assignments left to right, so a CASE referencing a column assigned in the
    // same UPDATE would see the already-updated value - evaluated here instead).
    private static final String UPDATE_LOT_SQL =
            "UPDATE Auction_Lots SET filled_quantity = ?, status = ? WHERE lot_id = ?";
    private static final String INSERT_SETTLEMENT_SQL =
            "INSERT INTO Settlements (lot_id, bid_id, buyer_id, farmer_id, crop_id, clearing_price_per_quintal, "
            + "quantity_quintals, gross_amount, mandi_tax, buyer_debit, farmer_payout, settled_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Executes the full settlement transaction for one trade. Returns the
     * settlement enriched with its database id, or throws (after rollback) on
     * any business or data-access failure.
     */
    public Settlement processSettlement(Settlement settlement) {
        if (settlement == null) throw new IllegalArgumentException("settlement is required");
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        int previousIsolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean previousAutoCommit = true;
        try {
            connection = db.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            previousIsolation = connection.getTransactionIsolation();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            // 1) Pessimistic lock on the lot row; verify it is settleable.
            String lotStatus = null;
            BigDecimal lotQuantity = null;
            BigDecimal alreadyFilled = null;
            try (PreparedStatement statement = connection.prepareStatement(LOCK_LOT_SQL)) {
                statement.setInt(1, settlement.getLotId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        lotStatus = resultSet.getString("status");
                        lotQuantity = resultSet.getBigDecimal("quantity_quintals");
                        alreadyFilled = resultSet.getBigDecimal("filled_quantity");
                    }
                }
            }
            if (lotStatus == null) {
                throw new LotNotSettleableException(settlement.getLotId(), "NOT_FOUND");
            }
            if (LotStatus.SETTLED.name().equals(lotStatus) || LotStatus.CANCELLED.name().equals(lotStatus)) {
                throw new LotNotSettleableException(settlement.getLotId(), lotStatus);
            }

            // 2) Pessimistic lock on the buyer escrow row; verify coverage.
            BigDecimal escrowBalance = null;
            try (PreparedStatement statement = connection.prepareStatement(LOCK_BUYER_SQL)) {
                statement.setInt(1, settlement.getBuyerId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) escrowBalance = resultSet.getBigDecimal("escrow_balance");
                }
            }
            if (escrowBalance == null) {
                throw new MandiDataAccessException("Buyer " + settlement.getBuyerId() + " not found");
            }
            if (escrowBalance.compareTo(settlement.getBuyerDebit()) < 0) {
                throw new com.mandi.exception.InsufficientEscrowException(
                        settlement.getBuyerId(), settlement.getBuyerDebit(), escrowBalance);
            }

            // 3) Debit the buyer escrow (total cost + 2% mandi tax).
            try (PreparedStatement statement = connection.prepareStatement(DEBIT_BUYER_SQL)) {
                statement.setBigDecimal(1, settlement.getBuyerDebit());
                statement.setInt(2, settlement.getBuyerId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Escrow debit affected " + statement.getUpdateCount() + " rows");
                }
            }

            // 4) Accumulate the lot fill; flip to SETTLED when fully consumed.
            BigDecimal newFilled = alreadyFilled.add(settlement.getQuantityQuintals());
            if (newFilled.compareTo(lotQuantity) > 0) {
                throw new MandiDataAccessException("Trade of " + settlement.getQuantityQuintals()
                        + "q would over-fill lot " + settlement.getLotId() + " (already filled "
                        + alreadyFilled + " of " + lotQuantity + "q) - rolled back");
            }
            String newLotStatus = newFilled.compareTo(lotQuantity) == 0
                    ? LotStatus.SETTLED.name() : LotStatus.MATCHED.name();
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_LOT_SQL)) {
                statement.setBigDecimal(1, newFilled);
                statement.setString(2, newLotStatus);
                statement.setInt(3, settlement.getLotId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Lot fill update affected " + statement.getUpdateCount() + " rows");
                }
            }

            // 5) Insert the settlement ledger row.
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SETTLEMENT_SQL, Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, settlement.getLotId());
                statement.setInt(2, settlement.getBidId());
                statement.setInt(3, settlement.getBuyerId());
                statement.setInt(4, settlement.getFarmerId());
                statement.setInt(5, settlement.getCropId());
                statement.setBigDecimal(6, settlement.getClearingPricePerQuintal());
                statement.setBigDecimal(7, settlement.getQuantityQuintals());
                statement.setBigDecimal(8, settlement.getGrossAmount());
                statement.setBigDecimal(9, settlement.getMandiTax());
                statement.setBigDecimal(10, settlement.getBuyerDebit());
                statement.setBigDecimal(11, settlement.getFarmerPayout());
                statement.setTimestamp(12, Timestamp.from(settlement.getSettledAt()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) settlement.setSettlementId(keys.getInt(1));
                }
            }

            // 6) Commit - everything above is now durable in MySQL.
            connection.commit();
            return settlement;
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly(connection);
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new MandiDataAccessException("Settlement failed for lot " + settlement.getLotId()
                    + " - transaction rolled back", failure);
        } finally {
            restoreAndRelease(db, connection, previousIsolation, previousAutoCommit);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            System.err.println("[SETTLEMENT] Rollback itself failed: " + rollbackFailure.getMessage());
        }
    }

    private static void restoreAndRelease(DatabaseConnection db, Connection connection,
                                          int previousIsolation, boolean previousAutoCommit) {
        if (connection == null) return;
        try {
            connection.setTransactionIsolation(previousIsolation);
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException ignored) {
            // connection is being returned anyway
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Every committed settlement, joined with crop/buyer/farmer names for reporting. */
    public List<Settlement> findAll() {
        String sql = """
                SELECT settlement_id, lot_id, bid_id, buyer_id, farmer_id, crop_id,
                       clearing_price_per_quintal, quantity_quintals, gross_amount, mandi_tax,
                       buyer_debit, farmer_payout, settled_at
                FROM Settlements ORDER BY settlement_id
                """;
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Settlement> settlements = new ArrayList<>();
                while (resultSet.next()) {
                    Settlement settlement = new Settlement(resultSet.getInt("lot_id"), resultSet.getInt("bid_id"),
                            resultSet.getInt("buyer_id"), resultSet.getInt("farmer_id"), resultSet.getInt("crop_id"),
                            resultSet.getBigDecimal("clearing_price_per_quintal"),
                            resultSet.getBigDecimal("quantity_quintals"),
                            resultSet.getBigDecimal("gross_amount"),
                            resultSet.getBigDecimal("mandi_tax"),
                            resultSet.getBigDecimal("buyer_debit"),
                            resultSet.getBigDecimal("farmer_payout"),
                            resultSet.getTimestamp("settled_at").toInstant());
                    settlement.setSettlementId(resultSet.getInt("settlement_id"));
                    settlements.add(settlement);
                }
                return settlements;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load settlements", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public int countSettlements() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM Settlements");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to count settlements", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public BigDecimal totalMandiTaxCollected() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(mandi_tax), 0) FROM Settlements");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to sum mandi tax", e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
