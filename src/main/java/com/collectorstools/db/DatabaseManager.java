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

    private void initSchema() {
        try (Statement st = connection.createStatement()) {

            // Tabela dłużników
            st.execute("""
                CREATE TABLE IF NOT EXISTS debtors (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    first_name  TEXT NOT NULL,
                    last_name   TEXT NOT NULL,
                    pesel       TEXT,
                    id_number   TEXT,
                    phone       TEXT,
                    email       TEXT,
                    street      TEXT,
                    city        TEXT,
                    postal_code TEXT,
                    notes       TEXT,
                    urgent      INTEGER NOT NULL DEFAULT 0
                )
            """);

            // Tabela spraw/długów
            st.execute("""
                CREATE TABLE IF NOT EXISTS debts (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    debtor_id        INTEGER NOT NULL,
                    case_number      TEXT NOT NULL,
                    creditor         TEXT NOT NULL,
                    total_amount     REAL NOT NULL,
                    remaining_amount REAL NOT NULL,
                    debt_date        TEXT,
                    description      TEXT,
                    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
                )
            """);

            // Tabela deklaracji
            st.execute("""
                CREATE TABLE IF NOT EXISTS declarations (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    debtor_id        INTEGER NOT NULL,
                    debt_id          INTEGER,
                    promised_date    TEXT NOT NULL,
                    promised_amount  REAL NOT NULL,
                    status           TEXT NOT NULL DEFAULT 'OCZEKUJACA',
                    broken_reason    TEXT,
                    payment_date     TEXT,
                    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE,
                    FOREIGN KEY (debt_id)   REFERENCES debts(id)   ON DELETE SET NULL
                )
            """);

            // Tabela interakcji
            st.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    debtor_id        INTEGER NOT NULL,
                    interaction_date TEXT NOT NULL,
                    contact_type     TEXT NOT NULL,
                    notes            TEXT,
                    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
                )
            """);

            // Tabela historii zmian dłużnika (audyt)
            st.execute("""
                CREATE TABLE IF NOT EXISTS debtor_audit (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    debtor_id   INTEGER NOT NULL,
                    changed_at  TEXT NOT NULL,
                    field_name  TEXT NOT NULL,
                    old_value   TEXT,
                    new_value   TEXT,
                    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
                )
            """);

            // Migracje dla istniejących baz (kolumny dodane po pierwszym wydaniu)
            addColumnIfMissing("debtors", "urgent", "INTEGER NOT NULL DEFAULT 0");

            System.out.println("Schemat bazy danych zainicjalizowany.");

        } catch (SQLException e) {
            throw new RuntimeException("Błąd inicjalizacji schematu bazy: " + e.getMessage(), e);
        }
    }

    /**
     * Dodaje kolumnę do istniejącej tabeli, jeśli jeszcze nie istnieje (lekka migracja).
     */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement st = connection.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
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