package com.collectorstools.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final String DB_FILE = "collectorstools.db";
    private static final String URL = "jdbc:sqlite:" + DB_FILE;

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        connect();
        initSchema();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    public Connection getConnection() {
        return connection;
    }

    /** Ścieżka pliku bazy danych (do kopii zapasowej / przywracania). */
    public String getDbPath() {
        return DB_FILE;
    }

    private void connect() {
        try {
            connection = DriverManager.getConnection(URL);
            // Włącz klucze obce w SQLite (domyślnie wyłączone)
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            System.out.println("Połączono z bazą danych: " + DB_FILE);
        } catch (SQLException e) {
            throw new RuntimeException("Nie można połączyć się z bazą danych: " + e.getMessage(), e);
        }
    }

    /**
     * Doprowadza schemat bazy do najnowszej wersji.
     *
     * <p>Definicje tabel nie są trzymane w tej klasie, tylko w plikach migracji
     * ({@code src/main/resources/db/migrations}) — patrz {@link SchemaManager}.
     * Ten sam zestaw migracji stosują testy, więc schemat testowy nie może
     * rozjechać się z produkcyjnym.
     */
    private void initSchema() {
        new SchemaManager(connection).migrate();
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Połączenie z bazą zamknięte.");
            }
        } catch (SQLException e) {
            System.err.println("Błąd przy zamykaniu połączenia: " + e.getMessage());
        }
    }
}