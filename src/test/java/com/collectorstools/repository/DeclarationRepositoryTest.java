package com.collectorstools.repository;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeclarationRepositoryTest {

    private Connection conn;
    private DeclarationRepository repository;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new DeclarationRepository(conn);
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Declaration decl(LocalDate date, String amount, DeclarationStatus status) {
        return Declaration.builder()
                .debtorId(debtorId)
                .promisedDate(date)
                .promisedAmount(new BigDecimal(amount))
                .status(status)
                .build();
    }

    @Test
    void saveAndFindById() throws Exception {
        Declaration saved = repository.save(decl(LocalDate.now(), "100", DeclarationStatus.PENDING));
        assertNotNull(saved.getId());
        assertEquals(DeclarationStatus.PENDING, repository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void refreshOverdueMarksPastPendingAsBroken() throws Exception {
        repository.save(decl(LocalDate.now().minusDays(5), "100", DeclarationStatus.PENDING));
        repository.save(decl(LocalDate.now().plusDays(5), "100", DeclarationStatus.PENDING));

        repository.refreshOverdue();

        assertEquals(1, repository.countByStatus(DeclarationStatus.BROKEN));
        assertEquals(1, repository.countByStatus(DeclarationStatus.PENDING));
    }

    @Test
    void findByStatusReturnsOnlyMatchingOrderedByDate() throws Exception {
        repository.save(decl(LocalDate.now().plusDays(10), "100", DeclarationStatus.PENDING));
        repository.save(decl(LocalDate.now().plusDays(2), "100", DeclarationStatus.PENDING));
        repository.save(decl(LocalDate.now(), "100", DeclarationStatus.FULFILLED));

        List<Declaration> pending = repository.findByStatus(DeclarationStatus.PENDING);
        assertEquals(2, pending.size());
        assertTrue(pending.get(0).getPromisedDate().isBefore(pending.get(1).getPromisedDate()));
    }

    @Test
    void findByDateRangeFiltersInclusive() throws Exception {
        repository.save(decl(LocalDate.now().plusDays(3), "100", DeclarationStatus.PENDING));
        repository.save(decl(LocalDate.now().plusDays(30), "100", DeclarationStatus.PENDING));

        List<Declaration> within = repository.findByDateRange(LocalDate.now(), LocalDate.now().plusDays(7));
        assertEquals(1, within.size());
    }

    @Test
    void updatePreservesDebtId() throws Exception {
        Declaration saved = repository.save(decl(LocalDate.now(), "100", DeclarationStatus.PENDING));
        saved.setStatus(DeclarationStatus.FULFILLED);
        saved.setPaymentDate(LocalDate.now());
        repository.update(saved);

        Declaration found = repository.findById(saved.getId()).orElseThrow();
        assertEquals(DeclarationStatus.FULFILLED, found.getStatus());
        assertNull(found.getDebtId());
    }
}
