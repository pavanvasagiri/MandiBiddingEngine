package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.Farmer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Persistence for {@link Farmer} master data. */
public final class FarmerDAO {

    /** Inserts a farmer and returns the generated farmer_id (also set on the object). */
    public int insert(Farmer farmer) {
        String sql = "INSERT INTO Farmers (name, village, bank_account_no) VALUES (?, ?, ?)";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, farmer.getName());
                statement.setString(2, farmer.getVillage());
                statement.setString(3, farmer.getBankAccountNo());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) farmer.setFarmerId(keys.getInt(1));
                }
            }
            if (farmer.getFarmerId() == null) {
                throw new MandiDataAccessException("Insert returned no generated key for farmer " + farmer.getName());
            }
            return farmer.getFarmerId();
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to insert farmer " + farmer.getName(), e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public List<Farmer> findAll() {
        String sql = "SELECT farmer_id, name, village, bank_account_no FROM Farmers ORDER BY farmer_id";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Farmer> farmers = new ArrayList<>();
                while (resultSet.next()) {
                    Farmer farmer = new Farmer(resultSet.getString("name"),
                            resultSet.getString("village"),
                            resultSet.getString("bank_account_no"));
                    farmer.setFarmerId(resultSet.getInt("farmer_id"));
                    farmers.add(farmer);
                }
                return farmers;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load farmers", e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
