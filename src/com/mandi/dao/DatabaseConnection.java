package com.mandi.dao;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight, thread-safe JDBC connection pool for MySQL.
 *
 * <p>Configuration is loaded from {@code config/db.properties} (overridable via
 * the {@code MANDI_DB_URL} / {@code MANDI_DB_USER} / {@code MANDI_DB_PASSWORD}
 * environment variables, so secrets can be injected by the deployment
 * environment). The pool is sized by {@code db.pool.size} (default 10) and
 * warmed up eagerly at {@link #init} time so a broken database fails fast.
 *
 * <p>Usage pattern: {@code getConnection()} ... work ... always
 * {@code releaseConnection(conn)} in a {@code finally} block. Connections are
 * validated on checkout and auto-recreated if the server dropped them.
 */
public final class DatabaseConnection {

    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final long CHECKOUT_TIMEOUT_SECONDS = 30;

    private static volatile DatabaseConnection instance;

    private final Properties config;
    private final String url;
    private final String user;
    private final String password;
    private final int poolSize;
    private final BlockingQueue<Connection> pool;

    static {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("MySQL Connector/J not found on the classpath: " + e);
        }
    }

    private DatabaseConnection(Properties config) {
        this.config = config;
        this.url = requireConfig("db.url");
        this.user = requireConfig("db.user");
        this.password = config.getProperty("db.password", "");
        this.poolSize = Integer.parseInt(config.getProperty("db.pool.size", "10"));
        if (poolSize <= 0) throw new IllegalStateException("db.pool.size must be positive");
        this.pool = new LinkedBlockingQueue<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                pool.offer(createConnection());
            }
        } catch (SQLException e) {
            shutdown();
            throw new IllegalStateException("Cannot reach MySQL at " + url + " - check server, credentials and network", e);
        }
    }

    /** Initialises the singleton pool. Must be called once before any DAO is used. */
    public static synchronized DatabaseConnection init(Properties config) {
        if (instance != null) throw new IllegalStateException("DatabaseConnection has already been initialised");
        instance = new DatabaseConnection(config);
        return instance;
    }

    public static DatabaseConnection getInstance() {
        DatabaseConnection db = instance;
        if (db == null) throw new IllegalStateException("DatabaseConnection.init(config) must be called first");
        return db;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Reads {@code config/db.properties} and applies environment-variable overrides. */
    public static Properties loadConfig(Path propertiesFile) throws IOException {
        Properties fileProps = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            fileProps.load(in);
        }
        String serverUrl = fileProps.getProperty("db.server.url");
        String dbName = fileProps.getProperty("db.name");
        String params = fileProps.getProperty("db.params", "");
        if (serverUrl == null || dbName == null) {
            throw new IOException("db.server.url and db.name are required in " + propertiesFile);
        }
        String url = System.getenv("MANDI_DB_URL");
        if (url == null || url.isBlank()) {
            url = serverUrl + "/" + dbName + params;
        }
        fileProps.setProperty("db.url", url);
        String user = System.getenv("MANDI_DB_USER");
        if (user != null && !user.isBlank()) fileProps.setProperty("db.user", user);
        String password = System.getenv("MANDI_DB_PASSWORD");
        if (password != null) fileProps.setProperty("db.password", password);
        return fileProps;
    }

    // ------------------------------------------------------------------
    // Pool accessors
    // ------------------------------------------------------------------

    /** Borrows a connection from the pool, validating (and if necessary recreating) it. */
    public Connection getConnection() throws SQLException {
        try {
            Connection connection = pool.poll(CHECKOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (connection == null) {
                throw new SQLException("Connection pool exhausted after " + CHECKOUT_TIMEOUT_SECONDS + "s");
            }
            if (!connection.isValid(3)) {
                closeQuietly(connection);
                connection = createConnection();
            }
            return connection;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a pooled connection", e);
        }
    }

    /** Returns a borrowed connection to the pool. Safe to call with null. */
    public void releaseConnection(Connection connection) {
        if (connection == null) return;
        try {
            if (connection.isClosed()) return;
            if (!connection.getAutoCommit()) connection.setAutoCommit(true);
        } catch (SQLException e) {
            closeQuietly(connection);
            return;
        }
        if (!pool.offer(connection)) {
            closeQuietly(connection);
        }
    }

    /** Closes every pooled connection. Called once at application shutdown. */
    public synchronized void shutdown() {
        Connection connection;
        while ((connection = pool.poll()) != null) {
            closeQuietly(connection);
        }
    }

    public int poolSize() {
        return poolSize;
    }

    public String jdbcUrl() {
        return url;
    }

    /** Read access to the resolved configuration (used by DatabaseInitializer). */
    public String configValue(String key) {
        return config.getProperty(key);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private String requireConfig(String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required configuration key: " + key);
        }
        return value;
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // already closed or broken - nothing further to do
        }
    }
}
