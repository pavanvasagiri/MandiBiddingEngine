package com.mandi;

import com.mandi.api.MandiHttpServer;
import com.mandi.dao.SettlementDAO;
import com.mandi.engine.DoubleAuctionEngine;
import com.mandi.util.DBConnection;

import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        try (var connection = DBConnection.getConnection()) {
            if (!connection.isValid(5)) throw new IllegalStateException("Database connection is not valid");
            System.out.println("Database connection established.");
        }
        String apiKey = System.getenv("MANDI_API_KEY");
        MandiHttpServer server = new MandiHttpServer(8080, new DoubleAuctionEngine(), new SettlementDAO(), apiKey);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mandi-http-shutdown"));
        server.start();
        System.out.println("Mandi bidding API listening on http://localhost:8080");
        new CountDownLatch(1).await(); // Keep the JVM alive while HttpServer runs in background threads.
    }
}
