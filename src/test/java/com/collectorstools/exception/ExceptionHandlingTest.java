package com.collectorstools.exception;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.service.DebtService;
import com.collectorstools.service.DebtorService;
import com.collectorstools.service.DebtorAuditService;
import com.collectorstools.repository.DebtorAuditRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Podział na błędy, które użytkownik naprawi, i awarie, których nie naprawi.
 */
class ExceptionHandlingTest {

    private Connection conn;
    private DebtService debtService;
    private DebtorService debtorService;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        debtService = new DebtService(new DebtRepository(conn));
        debtorService = new DebtorService(new DebtorRepository(conn),
                new DebtorAuditService(new DebtorAuditRepository(conn)));
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    @DisplayName("Naruszenie reguły biznesowej zgłaszane jest jako BusinessRuleException")
    void businessRuleViolationsUseDedicatedType() {
        assertThrows(BusinessRuleException.class,
                () -> debtorService.save(Debtor.builder().lastName("Kowalski").build()));

        assertThrows(BusinessRuleException.class,
                () -> debtorService.save(Debtor.builder()
                        .firstName("Jan").lastName("Kowalski").pesel("12345678901").build()));
    }

    @Test
    @DisplayName("BusinessRuleException pozostaje IllegalArgumentException")
    void businessRuleExceptionStaysCompatible() {
        BusinessRuleException error = new BusinessRuleException("Kwota musi być dodatnia.");

        assertTrue(error instanceof IllegalArgumentException,
                "Zgodność wsteczna: wcześniejsze przechwytywanie IllegalArgumentException musi działać");
        assertEquals("Kwota musi być dodatnia.", error.getMessage());
    }

    @Test
    @DisplayName("Komunikat reguły biznesowej nadaje się do pokazania bez zmian")
    void businessMessageIsUserReadable() {
        long debtorId = debtorService.save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
                () -> debtService.save(Debt.builder()
                        .debtorId(debtorId)
                        .caseNumber("SPR/1")
                        .creditor("ALIOR")
                        .totalAmount(new BigDecimal("1000.00"))
                        .remainingAmount(new BigDecimal("1000.00"))
                        .principalAmount(new BigDecimal("100.00"))
                        .build()));

        String message = error.getMessage();
        assertFalse(message.contains("Exception"), "Bez nazw klas: " + message);
        assertFalse(message.contains("SQL"), "Bez szczegółów bazy: " + message);
        assertFalse(message.contains("com.collectorstools"), "Bez pakietów: " + message);
        assertTrue(message.contains("1000"), "Z konkretną kwotą: " + message);
    }

    @Test
    @DisplayName("Awaria techniczna rozdziela komunikat dla użytkownika i szczegóły do logu")
    void technicalExceptionSeparatesMessages() {
        SQLException cause = new SQLException("[SQLITE_BUSY] database is locked");
        TechnicalException error = TechnicalException.database("zapisać wpłaty", cause);

        String userMessage = error.getUserMessage();
        assertTrue(userMessage.contains("zapisać wpłaty"));
        assertTrue(userMessage.contains("nie zostały zmienione"),
                "Użytkownik musi wiedzieć, że nie ma połowicznego zapisu");
        assertFalse(userMessage.contains("SQLITE_BUSY"),
                "Kod błędu sterownika nic użytkownikowi nie mówi");

        assertTrue(error.getMessage().contains("SQLITE_BUSY"),
                "Szczegóły techniczne muszą zostać zachowane dla logu");
        assertSame(cause, error.getCause());
    }

    @Test
    @DisplayName("Awaria techniczna zachowuje pierwotną przyczynę")
    void technicalExceptionKeepsCause() {
        SQLException cause = new SQLException("disk I/O error");
        TechnicalException error = new TechnicalException("Nie udało się zapisać danych.", cause);

        assertNotNull(error.getCause());
        assertEquals("disk I/O error", error.getCause().getMessage());
        assertEquals("Nie udało się zapisać danych.", error.getUserMessage());
    }
}
