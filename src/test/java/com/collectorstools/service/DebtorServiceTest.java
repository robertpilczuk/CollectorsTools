package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.DebtorAudit;
import com.collectorstools.repository.DebtorAuditRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebtorServiceTest {

    private Connection conn;
    private DebtorService service;
    private DebtorAuditRepository auditRepository;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        auditRepository = new DebtorAuditRepository(conn);
        service = new DebtorService(
                new DebtorRepository(conn),
                new DebtorAuditService(auditRepository));
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void validateRejectsBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(Debtor.builder().firstName("").lastName("Kowalski").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(Debtor.builder().firstName("Jan").lastName("").build()));
    }

    @Test
    void validateRejectsMalformedPesel() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(Debtor.builder().firstName("Jan").lastName("Kowalski").pesel("123").build()));
    }

    @Test
    void countAndSearchDelegateToRepository() {
        service.save(Debtor.builder().firstName("Jan").lastName("Kowalski").build());
        service.save(Debtor.builder().firstName("Anna").lastName("Nowak").build());
        assertEquals(2, service.count());
        assertEquals(1, service.search("nowak").size());
    }

    @Test
    void updateRecordsAuditForChangedFields() {
        Debtor saved = service.save(Debtor.builder().firstName("Jan").lastName("Kowalski").city("Lublin").build());

        saved.setCity("Kraków");
        saved.setUrgent(true);
        service.update(saved);

        List<DebtorAudit> history = auditRepository_findByDebtorId(saved.getId());
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(a -> a.getFieldName().equals("Miasto")
                && a.getOldValue().equals("Lublin") && a.getNewValue().equals("Kraków")));
        assertTrue(history.stream().anyMatch(a -> a.getFieldName().equals("Pilne")
                && a.getOldValue().equals("nie") && a.getNewValue().equals("tak")));
    }

    @Test
    void updateWithoutChangesRecordsNothing() {
        Debtor saved = service.save(Debtor.builder().firstName("Jan").lastName("Kowalski").build());
        service.update(saved);
        assertTrue(auditRepository_findByDebtorId(saved.getId()).isEmpty());
    }

    private List<DebtorAudit> auditRepository_findByDebtorId(long id) {
        try {
            return auditRepository.findByDebtorId(id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
