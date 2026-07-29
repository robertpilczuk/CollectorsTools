package com.collectorstools.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tworzy izolowane połączenie do bazy in-memory SQLite z pełnym schematem.
 * Każde wywołanie {@link #createTestConnection()} = świeża, pusta baza.
 */
public final class DatabaseTestHelper {

    private DatabaseTestHelper() {
    }

    public static Connection createTestConnection() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            createSchema(conn);
            return conn;
        } catch (SQLException e) {
            throw new RuntimeException("Nie udało się utworzyć testowej bazy: " + e.getMessage(), e);
        }
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
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
        }
    }
}
