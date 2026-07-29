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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migracja V3 przebudowuje tabelę debts. Przy włączonych kluczach obcych
 * DROP TABLE wykonuje niejawne DELETE FROM i uruchamia akcje ON DELETE tabel
 * zależnych — bez wyłączenia kluczy na czas migracji wyzerowałoby to debt_id
 * we wszystkich deklaracjach. Te testy pilnują, że tak się nie dzieje.
 */
class DebtRebuildMigrationTest {

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

    /**
     * Odtwarza bazę w kształcie wersji 2 — czyli sprzed migracji V3 — i wypełnia ją
     * sprawą oraz przypiętą do niej deklaracją. Schemat tworzony jest ręcznie, bo
     * {@code migrate()} stosuje od razu wszystkie dostępne migracje.
     */
    private void seedVersion2() throws SQLException {
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
                    status           TEXT NOT NULL DEFAULT 'PENDING',
                    broken_reason    TEXT,
                    cancelled_reason TEXT,
                    payment_date     TEXT,
                    created_at       TEXT NOT NULL,
                    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE,
                    FOREIGN KEY (debt_id)   REFERENCES debts(id)   ON DELETE SET NULL
                )
            """);

            st.executeUpdate(
                    "INSERT INTO debtors (id, first_name, last_name, phone) VALUES (1, 'Jan', 'Kowalski', '600100200')");
            st.executeUpdate("""
                INSERT INTO debts (id, debtor_id, case_number, creditor, total_amount, remaining_amount, description)
                VALUES (1, 1, 'SPR/2017/001', 'ALIOR', 5000, 4200, 'karta kredytowa')
            """);
            st.executeUpdate("""
                INSERT INTO declarations (id, debtor_id, debt_id, promised_date, promised_amount, status, created_at)
                VALUES (1, 1, 1, '2026-08-10', 500, 'PENDING', '2026-07-20')
            """);

            st.execute("PRAGMA user_version = 2");
        }
    }

    /**
     * Warunek początkowy dla testów: baza jest w wersji 2, deklaracja wskazuje na sprawę.
     */
    private void migrateToLatest() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT debt_id FROM declarations WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong("debt_id"));
        }
        new SchemaManager(conn).migrate();
    }

    @Test
    @DisplayName("Przebudowa debts nie zrywa powiązania deklaracji ze sprawą")
    void rebuildKeepsDeclarationLinkedToDebt() throws SQLException {
        seedVersion2();
        migrateToLatest();

        assertEquals(SchemaManager.latestVersion(), new SchemaManager(conn).currentVersion());

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT debt_id FROM declarations WHERE id = 1")) {
            assertTrue(rs.next());
            long debtId = rs.getLong("debt_id");
            assertFalse(rs.wasNull(), "ON DELETE SET NULL nie mogło się uruchomić podczas migracji");
            assertEquals(1, debtId);
        }
    }

    @Test
    @DisplayName("Dane sprawy przetrwały przebudowę, saldo trafiło do kapitału")
    void rebuildPreservesDebtDataAndBackfillsPrincipal() throws SQLException {
        seedVersion2();
        migrateToLatest();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT case_number, creditor, total_amount, remaining_amount,
                        principal_amount, interest_amount, cost_amount, description
                 FROM debts WHERE id = 1
             """)) {
            assertTrue(rs.next());
            assertEquals("SPR/2017/001", rs.getString("case_number"));
            assertEquals("ALIOR", rs.getString("creditor"));
            assertEquals(5000.0, rs.getDouble("total_amount"), 0.001);
            assertEquals(4200.0, rs.getDouble("remaining_amount"), 0.001);
            assertEquals(4200.0, rs.getDouble("principal_amount"), 0.001,
                    "Nieznane rozbicie salda trafia w całości do kapitału");
            assertEquals(0.0, rs.getDouble("interest_amount"), 0.001);
            assertEquals(0.0, rs.getDouble("cost_amount"), 0.001);
            assertEquals("karta kredytowa", rs.getString("description"));
        }
    }

    @Test
    @DisplayName("Saldo ujemne jest odrzucane przez ograniczenie CHECK")
    void negativeBalanceIsRejected() throws SQLException {
        seedVersion2();
        migrateToLatest();

        boolean rejected = false;
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE debts SET remaining_amount = -1 WHERE id = 1");
        } catch (SQLException e) {
            rejected = true;
        }
        assertTrue(rejected, "CHECK (remaining_amount >= 0) musi blokować ujemne saldo");
    }

    @Test
    @DisplayName("Numer telefonu dłużnika przeniesiony do tabeli debtor_phones")
    void migratesExistingPhoneToPhoneTable() throws SQLException {
        seedVersion2();
        migrateToLatest();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT phone_number, is_primary, source FROM debtor_phones WHERE debtor_id = 1")) {
            assertTrue(rs.next(), "Numer z kolumny debtors.phone musi trafić do nowej tabeli");
            assertEquals("600100200", rs.getString("phone_number"));
            assertEquals(1, rs.getInt("is_primary"), "Przeniesiony numer jest numerem podstawowym");
            assertEquals("MIGRACJA", rs.getString("source"));
            assertFalse(rs.next(), "Tylko jeden numer w bazie źródłowej");
        }
    }

    @Test
    @DisplayName("Klucze obce działają po migracji")
    void foreignKeysRestoredAfterMigration() throws SQLException {
        seedVersion2();
        migrateToLatest();

        boolean rejected = false;
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                INSERT INTO debts (debtor_id, case_number, creditor, total_amount, remaining_amount)
                VALUES (999, 'SPR/X', 'Bank', 100, 100)
            """);
        } catch (SQLException e) {
            rejected = true;
        }
        assertTrue(rejected, "Po migracji klucze obce muszą być z powrotem włączone");
    }

    @Test
    @DisplayName("Nowe kolumny sprawy są dostępne")
    void newDebtColumnsExist() throws SQLException {
        seedVersion2();
        migrateToLatest();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT original_creditor, portfolio, product, bank_account, case_category,
                        credit_status, settlement_allowed, court, court_signature,
                        service_start_date, enforcement_start_date
                 FROM debts WHERE id = 1
             """)) {
            // Sam SELECT rzuciłby wyjątkiem, gdyby którejś z kolumn brakowało
            assertTrue(rs.next());
        }
    }
}
