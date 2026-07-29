package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.repository.DeclarationRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeclarationServiceTest {

    private Connection conn;
    private DeclarationService service;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        service = new DeclarationService(new DeclarationRepository(conn));
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
    void validateRejectsInvalidDeclarations() {
        assertThrows(IllegalArgumentException.class, () ->
                service.save(Declaration.builder().promisedDate(LocalDate.now()).promisedAmount(BigDecimal.TEN).build()));
        assertThrows(IllegalArgumentException.class, () ->
                service.save(Declaration.builder().debtorId(debtorId).promisedAmount(BigDecimal.TEN).build()));
        assertThrows(IllegalArgumentException.class, () ->
                service.save(Declaration.builder().debtorId(debtorId).promisedDate(LocalDate.now())
                        .promisedAmount(BigDecimal.ZERO).build()));
    }

    @Test
    void saveDefaultsStatusToPending() {
        Declaration saved = service.save(Declaration.builder()
                .debtorId(debtorId).promisedDate(LocalDate.now()).promisedAmount(BigDecimal.TEN).build());
        assertEquals(DeclarationStatus.OCZEKUJACA, saved.getStatus());
    }

    @Test
    void findUpcomingReturnsOnlyPendingWithinWindow() {
        service.save(decl(LocalDate.now().plusDays(3), "100", DeclarationStatus.OCZEKUJACA));
        service.save(decl(LocalDate.now().plusDays(30), "100", DeclarationStatus.OCZEKUJACA));
        service.save(decl(LocalDate.now().plusDays(2), "100", DeclarationStatus.DOTRZYMANA));

        List<Declaration> upcoming = service.findUpcoming(7);
        assertEquals(1, upcoming.size());
        assertEquals(DeclarationStatus.OCZEKUJACA, upcoming.get(0).getStatus());
    }

    @Test
    void countBrokenAndSumByDebtor() {
        service.save(decl(LocalDate.now(), "100", DeclarationStatus.DOTRZYMANA));
        service.save(decl(LocalDate.now(), "200", DeclarationStatus.ZLAMANA));
        service.save(decl(LocalDate.now(), "300", DeclarationStatus.OCZEKUJACA));

        assertEquals(1, service.countBrokenByDebtorId(debtorId));
        // suma pomija ZLAMANA: 100 + 300
        assertEquals(0, new BigDecimal("400").compareTo(service.sumByDebtorId(debtorId)));
    }

    @Test
    void markAsFulfilledAndBrokenUpdateStatus() {
        Declaration saved = service.save(decl(LocalDate.now(), "100", DeclarationStatus.OCZEKUJACA));
        service.markAsFulfilled(saved.getId(), LocalDate.now());
        assertEquals(DeclarationStatus.DOTRZYMANA, service.findById(saved.getId()).orElseThrow().getStatus());

        service.markAsBroken(saved.getId(), "brak wpłaty");
        Declaration broken = service.findById(saved.getId()).orElseThrow();
        assertEquals(DeclarationStatus.ZLAMANA, broken.getStatus());
        assertEquals("brak wpłaty", broken.getBrokenReason());
    }
}
