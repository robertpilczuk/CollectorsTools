package com.collectorstools.db;

import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionManagerTest {

    private Connection conn;
    private TransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        transactionManager = new TransactionManager(conn);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @DisplayName("Udana operacja zatwierdza wszystkie zapisy")
    void commitsWhenOperationSucceeds() throws SQLException {
        transactionManager.execute(() -> {
            insertDebtor("Jan", "Kowalski");
            insertDebtor("Anna", "Nowak");
        });

        assertEquals(2, countDebtors());
    }

    @Test
    @DisplayName("Błąd SQL wycofuje zapisy wykonane przed nim")
    void rollsBackOnSqlException() throws SQLException {
        assertThrows(RuntimeException.class, () -> transactionManager.execute(() -> {
            insertDebtor("Jan", "Kowalski");
            // Naruszenie klucza obcego — sprawa dla nieistniejącego dłużnika
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    INSERT INTO debts (debtor_id, case_number, creditor, total_amount, remaining_amount)
                    VALUES (999, 'SPR/1', 'Bank', 1000, 1000)
                """);
            }
        }));

        assertEquals(0, countDebtors(), "Zapis sprzed błędu też musi zostać wycofany");
    }

    @Test
    @DisplayName("Wyjątek biznesowy wycofuje transakcję i zachowuje oryginalny komunikat")
    void rollsBackOnBusinessExceptionAndPreservesMessage() throws SQLException {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> transactionManager.execute(() -> {
                    insertDebtor("Jan", "Kowalski");
                    throw new IllegalArgumentException("Kwota wpłaty przekracza saldo sprawy.");
                }));

        assertEquals("Kwota wpłaty przekracza saldo sprawy.", thrown.getMessage(),
                "UI musi dostać oryginalny komunikat, nie opakowany");
        assertEquals(0, countDebtors());
    }

    @Test
    @DisplayName("Operacja może zwrócić wynik")
    void returnsValueFromOperation() throws SQLException {
        Long id = transactionManager.executeWithResult(() -> {
            insertDebtor("Jan", "Kowalski");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM debtors")) {
                return rs.next() ? rs.getLong(1) : null;
            }
        });

        assertEquals(1L, id);
        assertEquals(1, countDebtors());
    }

    @Test
    @DisplayName("Zagnieżdżone wywołanie dołącza do transakcji zewnętrznej")
    void nestedExecutionJoinsOuterTransaction() throws SQLException {
        assertThrows(IllegalStateException.class, () -> transactionManager.execute(() -> {
            insertDebtor("Jan", "Kowalski");

            // Wewnętrzna transakcja kończy się sukcesem, ale nie zatwierdza samodzielnie
            transactionManager.execute(() -> insertDebtor("Anna", "Nowak"));

            throw new IllegalStateException("Błąd po zagnieżdżonej operacji");
        }));

        assertEquals(0, countDebtors(),
                "Zapis z wewnętrznego execute musi zostać wycofany razem z zewnętrznym");
    }

    @Test
    @DisplayName("Autocommit wraca do stanu wyjściowego po sukcesie i po błędzie")
    void restoresAutoCommitAfterwards() throws SQLException {
        transactionManager.execute(() -> insertDebtor("Jan", "Kowalski"));
        assertTrue(conn.getAutoCommit(), "Autocommit po udanej transakcji");

        assertThrows(RuntimeException.class, () -> transactionManager.execute(() -> {
            throw new IllegalStateException("celowy błąd");
        }));
        assertTrue(conn.getAutoCommit(), "Autocommit po wycofanej transakcji");
    }

    // ===== pomocnicze =====

    private void insertDebtor(String firstName, String lastName) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO debtors (first_name, last_name) VALUES ('"
                    + firstName + "', '" + lastName + "')");
        }
    }

    private int countDebtors() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM debtors")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
