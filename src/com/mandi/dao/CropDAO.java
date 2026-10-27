package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;
import com.mandi.model.Crop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Persistence for {@link Crop} master data (name + MSP). */
public final class CropDAO {

    /** Inserts a crop and returns the generated crop_id (also set on the object). */
    public int insert(Crop crop) {
        String sql = "INSERT INTO Crops (crop_name, msp_price_per_quintal) VALUES (?, ?)";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, crop.getCropName());
                statement.setBigDecimal(2, crop.getMspPricePerQuintal());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) crop.setCropId(keys.getInt(1));
                }
            }
            if (crop.getCropId() == null) {
                throw new MandiDataAccessException("Insert returned no generated key for crop " + crop.getCropName());
            }
            return crop.getCropId();
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to insert crop " + crop.getCropName(), e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    public List<Crop> findAll() {
        String sql = "SELECT crop_id, crop_name, msp_price_per_quintal FROM Crops ORDER BY crop_id";
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                List<Crop> crops = new ArrayList<>();
                while (resultSet.next()) {
                    Crop crop = new Crop(resultSet.getString("crop_name"), resultSet.getBigDecimal("msp_price_per_quintal"));
                    crop.setCropId(resultSet.getInt("crop_id"));
                    crops.add(crop);
                }
                return crops;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load crops", e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
