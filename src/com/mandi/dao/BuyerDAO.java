package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.Buyer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Persistence for {@link Buyer} master data and escrow reporting reads. */
public final class BuyerDAO {

    /** Inserts a buyer with an escrow balance and returns the generated buyer_id. */
    public int insert(Buyer buyer) {
        String sql = "INSERT INTO Buyers (name, escrow_balance) VALUES (?, ?)";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, buyer.getName());
                statement.setBigDecimal(2, buyer.getEscrowBalance());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) buyer.setBuyerId(keys.getInt(1));
                }
            }
            if (buyer.getBuyerId() == null) {
                throw new MandiDataAccessException("Insert returned no generated key for buyer " + buyer.getName());
            }
            return buyer.getBuyerId();
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to insert buyer " + buyer.getName(), e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public List<Buyer> findAll() {
        String sql = "SELECT buyer_id, name, escrow_balance FROM Buyers ORDER BY buyer_id";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Buyer> buyers = new ArrayList<>();
                while (resultSet.next()) {
                    Buyer buyer = new Buyer(resultSet.getString("name"), resultSet.getBigDecimal("escrow_balance"));
                    buyer.setBuyerId(resultSet.getInt("buyer_id"));
                    buyers.add(buyer);
                }
                return buyers;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load buyers", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Post-commit reporting read of one buyer's escrow balance. */
    public BigDecimal findEscrowBalance(int buyerId) {
        String sql = "SELECT escrow_balance FROM Buyers WHERE buyer_id = ?";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, buyerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getBigDecimal("escrow_balance") : null;
                }
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to read escrow balance for buyer " + buyerId, e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
