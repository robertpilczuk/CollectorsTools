package com.collectorstools.service;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class DebtServiceTest {

    private Connection conn;
    private DebtService service;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        service = new DebtService(new DebtRepository(conn));
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Debt debt(String total, String remaining) {
        return Debt.builder()
                .debtorId(debtorId)
                .caseNumber("S1")
                .creditor("Bank A")
                .totalAmount(new BigDecimal(total))
                .remainingAmount(new BigDecimal(remaining))
                .build();
    }

    @Test
    void validateRejectsInvalidDebts() {
        assertThrows(IllegalArgumentException.class, () ->
                service.save(Debt.builder().debtorId(debtorId).creditor("Bank")
                        .totalAmount(BigDecimal.TEN).remainingAmount(BigDecimal.ONE).build())); // brak caseNumber
        assertThrows(IllegalArgumentException.class, () ->
                service.save(Debt.builder().debtorId(debtorId).caseNumber("S1").creditor("Bank")
                        .totalAmount(BigDecimal.ZERO).remainingAmount(BigDecimal.ZERO).build())); // total <= 0
    }

    @Test
    void getTotalRemainingForDebtorSums() {
        service.save(debt("1000", "400"));
        service.save(debt("2000", "600"));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getTotalRemainingForDebtor(debtorId)));
    }
}
