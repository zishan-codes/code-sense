package com.sce.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {

    private static final String DATABASE_URL = "jdbc:sqlite:smart_evaluator.db";

    private DatabaseManager() {
        // Utility class - no object creation
    }

    public static Connection getConnection() throws SQLException {

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver could not be loaded.", e);
        }

        return DriverManager.getConnection(DATABASE_URL);
    }

    public static void initializeDatabase() throws SQLException {

        String createTableSQL =
                "CREATE TABLE IF NOT EXISTS evaluations (" +
                "evaluation_id TEXT PRIMARY KEY, " +
                "timestamp TEXT NOT NULL, " +
                "class_name TEXT NOT NULL, " +
                "source_code TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "exit_code INTEGER NOT NULL, " +
                "stdout TEXT NOT NULL, " +
                "stderr TEXT NOT NULL, " +
                "ai_hint TEXT NOT NULL, " +
                "ai_suggested_fix TEXT NOT NULL, " +
                "ai_request_status TEXT NOT NULL, " +
                "duration_millis INTEGER NOT NULL" +
                ")";

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.executeUpdate(createTableSQL);
        }
    }
}