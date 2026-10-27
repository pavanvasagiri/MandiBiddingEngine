package com.mandi.server;

import com.mandi.dao.DatabaseConnection;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for running the mandi as a local web application.
 * Usage: {@code java -jar MandiBiddingEngine.jar server [port]} (default 8080,
 * overridable via the {@code MANDI_PORT} environment variable).
 */
public final class MandiServerMain {

    private MandiServerMain() { }

    public static void main(String[] args) throws Exception {
        int port;
        if (args.length > 0) {
            port = Integer.parseInt(args[0].trim());
        } else if (System.getenv("MANDI_PORT") != null && !System.getenv("MANDI_PORT").isBlank()) {
            port = Integer.parseInt(System.getenv("MANDI_PORT").trim());
        } else {
            port = 8080;
        }
        DatabaseConnection database = DatabaseConnection.init(DatabaseConnection.loadConfig(Path.of("config", "db.properties")));
        try {
            MandiHttpServer server = new MandiHttpServer(port);
            server.start();
            System.out.println("Open http://localhost:" + port + " in a browser to trade. Press Ctrl+C to stop the server.");
            new CountDownLatch(1).await(); // keep the JVM alive while HttpServer worker threads serve requests
        } finally {
            database.shutdown();
        }
    }
}
