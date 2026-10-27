package com.mandi.dao;

import com.mandi.exception.MandiDataAccessException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates (and, for deterministic demonstration runs, recreates) the
 * {@code mandi_db} schema directly from the application. The same DDL is
 * mirrored in {@code sql/mandi_schema_v2.sql} for DBA reference.
 *
 * <p>MySQL 8.0+ is required (window functions, CHECK constraints, ENUM).
 */
public final class DatabaseInitializer {

    private static final String[] TABLE_DDL_PART1 = {
            """
            CREATE TABLE IF NOT EXISTS Crops (
              crop_id INT AUTO_INCREMENT PRIMARY KEY,
              crop_name VARCHAR(80) NOT NULL UNIQUE,
              msp_price_per_quintal DECIMAL(10,2) NOT NULL,
              CONSTRAINT crops_chk_msp CHECK (msp_price_per_quintal > 0.00)
            ) ENGINE=InnoDB
            """,
            """
            CREATE TABLE IF NOT EXISTS Farmers (
              farmer_id INT AUTO_INCREMENT PRIMARY KEY,
              name VARCHAR(100) NOT NULL,
              village VARCHAR(100) NOT NULL,
              bank_account_no VARCHAR(24) NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB
            """,
            """
            CREATE TABLE IF NOT EXISTS Buyers (
              buyer_id INT AUTO_INCREMENT PRIMARY KEY,
              name VARCHAR(120) NOT NULL,
              escrow_balance DECIMAL(14,2) NOT NULL DEFAULT 0.00,
              CONSTRAINT buyers_chk_escrow CHECK (escrow_balance >= 0.00)
            ) ENGINE=InnoDB
            """,
            """
            CREATE TABLE IF NOT EXISTS Auction_Lots (
              lot_id INT AUTO_INCREMENT PRIMARY KEY,
              crop_id INT NOT NULL,
              farmer_id INT NOT NULL,
              quantity_quintals DECIMAL(10,2) NOT NULL,
              reserve_price_per_quintal DECIMAL(10,2) NOT NULL,
              filled_quantity DECIMAL(10,2) NOT NULL DEFAULT 0.00,
              status ENUM('OPEN','MATCHED','SETTLED','CANCELLED') NOT NULL DEFAULT 'OPEN',
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              CONSTRAINT fk_lot_crop FOREIGN KEY (crop_id) REFERENCES Crops(crop_id),
              CONSTRAINT fk_lot_farmer FOREIGN KEY (farmer_id) REFERENCES Farmers(farmer_id),
              CONSTRAINT lots_chk_qty CHECK (quantity_quintals > 0.00 AND filled_quantity >= 0.00)
            ) ENGINE=InnoDB
            """
    };

    private static final String[] TABLE_DDL_PART2 = {
            """
            CREATE TABLE IF NOT EXISTS Bids (
              bid_id INT AUTO_INCREMENT PRIMARY KEY,
              crop_id INT NOT NULL,
              buyer_id INT NOT NULL,
              bid_price_per_quintal DECIMAL(10,2) NOT NULL,
              quantity_quintals DECIMAL(10,2) NOT NULL,
              status ENUM('ACCEPTED','REJECTED_BELOW_MSP','REJECTED_ANOMALY') NOT NULL,
              anomaly_flag TINYINT NOT NULL DEFAULT 0,
              placed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              CONSTRAINT fk_bid_crop FOREIGN KEY (crop_id) REFERENCES Crops(crop_id),
              CONSTRAINT fk_bid_buyer FOREIGN KEY (buyer_id) REFERENCES Buyers(buyer_id),
              KEY idx_bids_crop_status (crop_id, status)
            ) ENGINE=InnoDB
            """,
            """
            CREATE TABLE IF NOT EXISTS Settlements (
              settlement_id INT AUTO_INCREMENT PRIMARY KEY,
              lot_id INT NOT NULL,
              bid_id INT NOT NULL,
              buyer_id INT NOT NULL,
              farmer_id INT NOT NULL,
              crop_id INT NOT NULL,
              clearing_price_per_quintal DECIMAL(10,2) NOT NULL,
              quantity_quintals DECIMAL(10,2) NOT NULL,
              gross_amount DECIMAL(14,2) NOT NULL,
              mandi_tax DECIMAL(12,2) NOT NULL,
              buyer_debit DECIMAL(14,2) NOT NULL,
              farmer_payout DECIMAL(14,2) NOT NULL,
              settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              CONSTRAINT fk_set_lot FOREIGN KEY (lot_id) REFERENCES Auction_Lots(lot_id),
              CONSTRAINT fk_set_bid FOREIGN KEY (bid_id) REFERENCES Bids(bid_id),
              CONSTRAINT fk_set_buyer FOREIGN KEY (buyer_id) REFERENCES Buyers(buyer_id),
              CONSTRAINT uq_settlement_bid_lot UNIQUE (bid_id, lot_id)
            ) ENGINE=InnoDB
            """,
            """
            CREATE TABLE IF NOT EXISTS Price_History (
              price_id INT AUTO_INCREMENT PRIMARY KEY,
              crop_id INT NOT NULL,
              trade_date DATE NOT NULL,
              avg_price_per_quintal DECIMAL(10,2) NOT NULL,
              CONSTRAINT uq_price_crop_date UNIQUE (crop_id, trade_date),
              CONSTRAINT fk_price_crop FOREIGN KEY (crop_id) REFERENCES Crops(crop_id)
            ) ENGINE=InnoDB
            """
    };

    /**
     * Drops and recreates {@code mandi_db} from scratch. Used when
     * {@code db.reset=true} so every simulation run starts from a known state
     * (a SQL dump of the previous state can be kept via mysqldump).
     */
    public void recreateSchema() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        String serverUrl = require(db.configValue("db.server.url"), "db.server.url");
        String params = db.configValue("db.params") == null ? "" : db.configValue("db.params");
        String dbName = require(db.configValue("db.name"), "db.name");
        String user = require(db.configValue("db.user"), "db.user");
        String password = db.configValue("db.password") == null ? "" : db.configValue("db.password");
        try (Connection connection = DriverManager.getConnection(serverUrl + params, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + dbName);
            statement.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new MandiDataAccessException("Unable to recreate schema " + dbName, e);
        }
    }

    /** Creates every table (idempotent - uses IF NOT EXISTS). */
    public void createTables() {
        DatabaseConnection db = DatabaseConnection.getInstance();
        Connection connection = null;
        try {
            connection = db.getConnection();
            try (Statement statement = connection.createStatement()) {
                for (String ddl : TABLE_DDL_PART1) statement.execute(ddl);
                for (String ddl : TABLE_DDL_PART2) statement.execute(ddl);
            }
        } catch (SQLException e) {
            throw new MandiDataAccessException("Schema creation failed", e);
        } finally {
            db.releaseConnection(connection);
        }
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration key: " + key);
        return value;
    }
}
