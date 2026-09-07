package com.mandi.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Database settings are supplied by the deployment environment, never source code. */
public final class DBConnection {
    private DBConnection() { }

    public static Connection getConnection() throws SQLException {
        String url = required("MANDI_DB_URL");
        String user = required("MANDI_DB_USER");
        String password = required("MANDI_DB_PASSWORD");
        return DriverManager.getConnection(url, user, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required environment variable: " + name);
        return value;
    }
}
