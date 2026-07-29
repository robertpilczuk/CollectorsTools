package com.collectorstools.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy wersjonowania i idempotentności migracji schematu.
 * Celowo nie korzystają z DatabaseTestHelper — sprawdzają warstwę,
 * na której ten helper jest zbudowany.
 */
class SchemaManagerTest {

    private static final List<String> EXPECTED_TABLES =
            List.of("debtors", "debts", "declarations", "interactions", "debtor_audit");

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @DisplayName("Świeża baza startuje z wersją 0")
    void freshDatabaseStartsAtVersionZero() throws SQLException {
        assertEquals(0, new SchemaManager(conn).currentVersion());
    }

    @Test
    @DisplayName("Migracja tworzy wszystkie tabele i podnosi wersję schematu")
    void migrateCreatesAllTablesAndSetsVersion() throws SQLException {
        SchemaManager schemaManager = new SchemaManager(conn);

        schemaManager.migrate();

        assertEquals(SchemaManager.latestVersion(), schemaManager.currentVersion());
        List<String> tables = existingTables();
        for (String expected : EXPECTED_TABLES) {
            assertTrue(tables.contains(expected), "Brak tabeli: " + expected);
        }
    }

    @Test
    @DisplayName("Ponowna migracja nic nie zmienia i nie kasuje danych")
    void migrateIsIdempotent() throws SQLException {
        SchemaManager schemaManager = new SchemaManager(conn);
        schemaManager.migrate();

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO debtors (first_name, last_name) VALUES ('Jan', 'Kowalski')");
        }

        schemaManager.migrate();

        assertEquals(SchemaManager.latestVersion(), schemaManager.currentVersion());
        assertEquals(1, countRows("debtors"), "Migracja nie może kasować istniejących danych");
    }

    @Test
    @DisplayName("Baza sprzed wersjonowania dostaje brakującą kolumnę urgent")
    void legacyDatabaseGetsMissingUrgentColumn() throws SQLException {
        // Odtworzenie schematu sprzed wprowadzenia kolumny urgent i wersjonowania —
        // pozostałe kolumny są takie jak w pierwszym wydaniu aplikacji.
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE debtors (
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
                    notes       TEXT
                )
            """);
            st.executeUpdate(
                    "INSERT INTO debtors (first_name, last_name, phone) VALUES ('Anna', 'Nowak', '600100200')");
        }
        assertFalse(columnExists("debtors", "urgent"), "Warunek początkowy: kolumny jeszcze nie ma");

        new SchemaManager(conn).migrate();

        assertTrue(columnExists("debtors", "urgent"), "Migracja powinna dołożyć kolumnę urgent");
        assertEquals(1, countRows("debtors"), "Dane ze starej bazy muszą przetrwać migrację");
    }

    @Test
    @DisplayName("Klucze obce działają po migracji")
    void foreignKeysAreEnforcedAfterMigration() throws SQLException {
        new SchemaManager(conn).migrate();

        boolean rejected = false;
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                INSERT INTO debts (debtor_id, case_number, creditor, total_amount, remaining_amount)
                VALUES (999, 'SPR/1', 'Bank', 1000, 1000)
            """);
        } catch (SQLException e) {
            rejected = true;
        }
        assertTrue(rejected, "Sprawa dla nieistniejącego dłużnika powinna zostać odrzucona");
    }

    // ===== pomocnicze =====

    private List<String> existingTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }
        }
        return tables;
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countRows(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
