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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza migrację V2 na bazie w kształcie sprzed zmiany statusów —
 * czyli takiej, jaką ma dziś użytkownik aplikacji.
 */
class DeclarationStatusMigrationTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            createV1Schema(st);
            seedLegacyData(st);
            // Baza jest w wersji 1 — migracja V2 jeszcze nie zastosowana
            st.execute("PRAGMA user_version = 1");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @DisplayName("Polskie statusy zostają przepisane na angielskie")
    void migratesPolishStatusesToEnglish() throws SQLException {
        new SchemaManager(conn).migrate();

        Map<String, Integer> statuses = statusCounts();
        assertEquals(1, statuses.getOrDefault("PENDING", 0));
        assertEquals(1, statuses.getOrDefault("FULFILLED", 0));
        assertEquals(1, statuses.getOrDefault("BROKEN", 0));
        assertEquals(0, statuses.getOrDefault("OCZEKUJACA", 0), "Stare wartości nie mogą zostać");
        assertEquals(0, statuses.getOrDefault("DOTRZYMANA", 0));
        assertEquals(0, statuses.getOrDefault("ZLAMANA", 0));
    }

    @Test
    @DisplayName("Dane deklaracji przetrwały przebudowę tabeli")
    void preservesExistingData() throws SQLException {
        new SchemaManager(conn).migrate();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, promised_amount, broken_reason, created_at FROM declarations WHERE id = 3")) {
            assertTrue(rs.next(), "Deklaracja o id=3 musi istnieć po migracji");
            assertEquals(300.0, rs.getDouble("promised_amount"), 0.001);
            assertEquals("brak środków", rs.getString("broken_reason"));
            assertNotNull(rs.getString("created_at"), "created_at musi zostać uzupełnione");
        }
    }

    @Test
    @DisplayName("created_at uzupełnione datą obietnicy, nowe kolumny dostępne")
    void backfillsCreatedAtAndAddsColumns() throws SQLException {
        new SchemaManager(conn).migrate();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT promised_date, created_at, cancelled_reason FROM declarations WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(rs.getString("promised_date"), rs.getString("created_at"));
            assertEquals(null, rs.getString("cancelled_reason"));
        }
        assertEquals(SchemaManager.latestVersion(), new SchemaManager(conn).currentVersion());
    }

    @Test
    @DisplayName("Ponowna migracja niczego nie psuje")
    void migrationIsIdempotent() throws SQLException {
        new SchemaManager(conn).migrate();
        new SchemaManager(conn).migrate();

        assertEquals(3, countDeclarations());
        assertEquals(1, statusCounts().getOrDefault("PENDING", 0));
    }

    // ===== pomocnicze =====

    private void createV1Schema(Statement st) throws SQLException {
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
                notes       TEXT,
                urgent      INTEGER NOT NULL DEFAULT 0
            )
        """);
        st.execute("""
            CREATE TABLE debts (
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
        st.execute("""
            CREATE TABLE declarations (
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
    }

    private void seedLegacyData(Statement st) throws SQLException {
        st.executeUpdate("INSERT INTO debtors (id, first_name, last_name) VALUES (1, 'Jan', 'Kowalski')");
        st.executeUpdate("""
            INSERT INTO declarations (id, debtor_id, promised_date, promised_amount, status, broken_reason)
            VALUES (1, 1, '2026-08-10', 100, 'OCZEKUJACA', NULL)
        """);
        st.executeUpdate("""
            INSERT INTO declarations (id, debtor_id, promised_date, promised_amount, status, payment_date)
            VALUES (2, 1, '2026-07-01', 200, 'DOTRZYMANA', '2026-07-01')
        """);
        st.executeUpdate("""
            INSERT INTO declarations (id, debtor_id, promised_date, promised_amount, status, broken_reason)
            VALUES (3, 1, '2026-06-15', 300, 'ZLAMANA', 'brak środków')
        """);
    }

    private Map<String, Integer> statusCounts() throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, COUNT(*) c FROM declarations GROUP BY status")) {
            while (rs.next()) {
                result.put(rs.getString("status"), rs.getInt("c"));
            }
        }
        return result;
    }

    private int countDeclarations() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM declarations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
