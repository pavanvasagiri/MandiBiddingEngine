package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Market price analytics backed by SQL window functions.
 *
 * <p>The 7-day rolling average is computed inside MySQL with
 * {@code AVG() OVER (PARTITION BY crop_id ORDER BY trade_date ROWS BETWEEN 6
 * PRECEDING AND CURRENT ROW)} - the database does the heavy lifting, the DAO
 * only maps rows. The latest rolling average per crop feeds the engine's
 * price-manipulation screen, and daily clearing prices are upserted back into
 * {@code Price_History} so the trend stays current.
 */
public final class MarketAnalyticsDAO {

    /** One row of the rolling-average series for a crop. */
    public record RollingAverage(int cropId, String cropName, LocalDate tradeDate,
                                 BigDecimal avgPrice, BigDecimal rolling7DayAvg) { }

    private static final String ROLLING_SERIES_SQL = """
            SELECT p.crop_id,
                   c.crop_name,
                   p.trade_date,
                   p.avg_price_per_quintal AS avg_price,
                   ROUND(AVG(p.avg_price_per_quintal) OVER (
                       PARTITION BY p.crop_id
                       ORDER BY p.trade_date
                       ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS rolling_7d_avg
            FROM Price_History p
            JOIN Crops c ON c.crop_id = p.crop_id
            ORDER BY p.crop_id, p.trade_date
            """;

    private static final String LATEST_ROLLING_PER_CROP_SQL = """
            SELECT crop_id, crop_name, rolling_7d_avg
            FROM (
              SELECT p.crop_id,
                     c.crop_name,
                     ROUND(AVG(p.avg_price_per_quintal) OVER (
                         PARTITION BY p.crop_id
                         ORDER BY p.trade_date
                         ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS rolling_7d_avg,
                     ROW_NUMBER() OVER (PARTITION BY p.crop_id ORDER BY p.trade_date DESC) AS recency_rank
              FROM Price_History p
              JOIN Crops c ON c.crop_id = p.crop_id
            ) ranked
            WHERE recency_rank = 1
            ORDER BY crop_id
            """;

    private static final String UPSERT_DAILY_AVERAGE_SQL = """
            INSERT INTO Price_History (crop_id, trade_date, avg_price_per_quintal)
            VALUES (?, ?, ?) AS new_row
            ON DUPLICATE KEY UPDATE avg_price_per_quintal = new_row.avg_price_per_quintal
            """;

    /** Full rolling-average series (every crop, every day) for trend reporting. */
    public List<RollingAverage> getRolling7DayAverages() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(ROLLING_SERIES_SQL);
                 ResultSet resultSet = statement.executeQuery()) {
                List<RollingAverage> series = new ArrayList<>();
                while (resultSet.next()) {
                    series.add(new RollingAverage(resultSet.getInt("crop_id"),
                            resultSet.getString("crop_name"),
                            resultSet.getDate("trade_date").toLocalDate(),
                            resultSet.getBigDecimal("avg_price"),
                            resultSet.getBigDecimal("rolling_7d_avg")));
                }
                return series;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to compute 7-day rolling averages", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Latest 7-day rolling average per crop - the anomaly screen's reference price. */
    public Map<Integer, BigDecimal> getLatest7DayRollingAveragePerCrop() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(LATEST_ROLLING_PER_CROP_SQL);
                 ResultSet resultSet = statement.executeQuery()) {
                Map<Integer, BigDecimal> latest = new HashMap<>();
                while (resultSet.next()) {
                    latest.put(resultSet.getInt("crop_id"), resultSet.getBigDecimal("rolling_7d_avg"));
                }
                return latest;
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to load latest rolling averages", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    /** Upserts one day's average mandi price for a crop (idempotent per crop+date). */
    public void upsertDailyAverage(int cropId, LocalDate tradeDate, BigDecimal avgPrice) {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_DAILY_AVERAGE_SQL)) {
                statement.setInt(1, cropId);
                statement.setDate(2, Date.valueOf(tradeDate));
                statement.setBigDecimal(3, avgPrice.setScale(2, java.math.RoundingMode.HALF_UP));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Failed to upsert daily average for crop " + cropId, e);
        } finally {
            db.releaseConnection(connection);
        }
    }
}
