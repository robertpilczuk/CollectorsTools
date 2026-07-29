package com.collectorstools.repository;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DebtRepositoryTest {

    private Connection conn;
    private DebtRepository repository;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new DebtRepository(conn);
        DebtorRepository debtorRepository = new DebtorRepository(conn);
        debtorId = debtorRepository.save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Debt debt(String caseNumber, String creditor, String total, String remaining) {
        return Debt.builder()
                .debtorId(debtorId)
                .caseNumber(caseNumber)
                .creditor(creditor)
                .totalAmount(new BigDecimal(total))
                .remainingAmount(new BigDecimal(remaining))
                .build();
    }

    @Test
    void saveAndFindByDebtorId() throws Exception {
        repository.save(debt("S1", "Bank A", "1000", "500"));
        repository.save(debt("S2", "Bank B", "2000", "2000"));
        assertEquals(2, repository.findByDebtorId(debtorId).size());
    }

    @Test
    void sumAllRemainingSumsAcrossDebts() throws Exception {
        repository.save(debt("S1", "Bank A", "1000", "500"));
        repository.save(debt("S2", "Bank B", "2000", "1500"));
        assertEquals(0, new BigDecimal("2000").compareTo(repository.sumAllRemaining()));
    }

    @Test
    void sumAllRemainingIsZeroWhenEmpty() throws Exception {
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.sumAllRemaining()));
    }

    @Test
    void findDebtorIdsByCreditorMatchesPartialCaseInsensitive() throws Exception {
        repository.save(debt("S1", "PKO Bank Polski", "1000", "500"));
        Set<Long> ids = repository.findDebtorIdsByCreditor("pko");
        assertTrue(ids.contains(debtorId));
        assertTrue(repository.findDebtorIdsByCreditor("inny").isEmpty());
    }

    @Test
    void deleteRemovesDebt() throws Exception {
        Debt saved = repository.save(debt("S1", "Bank A", "1000", "500"));
        repository.delete(saved.getId());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }
}
