package com.sce.database;

public class DatabaseTest {

    public static void main(String[] args) {

        try {
            DatabaseManager.initializeDatabase();

            System.out.println("Database initialized successfully!");
            System.out.println("Evaluations table is ready.");

        } catch (Exception e) {
            System.out.println("Database initialization failed!");
            e.printStackTrace();
        }
    }
}
