package com.collectorstools.repository;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.DebtorAudit;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebtorAuditRepositoryTest {

    private Connection conn;
    private DebtorAuditRepository repository;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new DebtorAuditRepository(conn);
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void savesAndReturnsNewestFirst() throws Exception {
        repository.save(DebtorAudit.builder()
                .debtorId(debtorId).changedAt(LocalDateTime.now().minusHours(1))
                .fieldName("Miasto").oldValue("Lublin").newValue("Kraków").build());
        repository.save(DebtorAudit.builder()
                .debtorId(debtorId).changedAt(LocalDateTime.now())
                .fieldName("Telefon").oldValue("111").newValue("222").build());

        List<DebtorAudit> history = repository.findByDebtorId(debtorId);
        assertEquals(2, history.size());
        assertEquals("Telefon", history.get(0).getFieldName());
    }
}
