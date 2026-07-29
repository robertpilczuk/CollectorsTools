package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.model.*;
import com.collectorstools.repository.*;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dziennik audytowy: kto, kiedy i co zmienił w danych mających skutki finansowe.
 */
class AuditTrailTest {

    private Connection conn;
    private AuditService auditService;
    private DebtorService debtorService;
    private DebtService debtService;
    private DeclarationService declarationService;
    private PaymentService paymentService;

    private long debtorId;
    private long debtId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();

        DebtorRepository debtorRepository = new DebtorRepository(conn);
        DebtRepository debtRepository = new DebtRepository(conn);
        DeclarationRepository declarationRepository = new DeclarationRepository(conn);

        auditService = new AuditService(new AuditLogRepository(conn));
        debtorService = new DebtorService(debtorRepository,
                new DebtorAuditService(new DebtorAuditRepository(conn)), auditService);
        debtService = new DebtService(debtRepository, auditService);
        declarationService = new DeclarationService(declarationRepository, auditService);
        paymentService = new PaymentService(new PaymentRepository(conn), debtRepository,
                declarationRepository, new TransactionManager(conn), auditService);

        debtorId = debtorService.save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").pesel("83120102021").build()).getId();

        debtId = debtService.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/001")
                .creditor("ALIOR")
                .totalAmount(new BigDecimal("1000.00"))
                .remainingAmount(new BigDecimal("1000.00"))
                .build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Payment pay(String amount, Long declarationId) {
        return paymentService.registerPayment(Payment.builder()
                .debtId(debtId)
                .declarationId(declarationId)
                .amount(new BigDecimal(amount))
                .paymentDate(LocalDate.now())
                .note("wpłata gotówką")
                .build());
    }

    private List<AuditEntry> entries(AuditOperation operation) {
        return auditService.findByDebtorId(debtorId).stream()
                .filter(entry -> entry.getOperation() == operation)
                .toList();
    }

    @Test
    @DisplayName("Rejestracja wpłaty trafia do dziennika z autorem i zmianą salda")
    void paymentIsAudited() {
        pay("300.00", null);

        List<AuditEntry> logged = entries(AuditOperation.PAYMENT_REGISTERED);
        assertEquals(1, logged.size());

        AuditEntry entry = logged.get(0);
        assertEquals(AuditEntity.PAYMENT, entry.getEntityType());
        assertEquals(debtorId, entry.getDebtorId());
        assertEquals("local", entry.getUserId(), "Autor pobierany z CurrentUser");
        assertNotNull(entry.getOccurredAt());
        assertEquals("Saldo sprawy", entry.getFieldName());
        assertEquals("1000,00", entry.getOldValue());
        assertEquals("700,00", entry.getNewValue());
        assertEquals("wpłata gotówką", entry.getReason());
    }

    @Test
    @DisplayName("Wycofanie wpłaty jest odnotowane osobno")
    void paymentReversalIsAudited() {
        Payment payment = pay("300.00", null);

        paymentService.reversePayment(payment.getId());

        assertEquals(1, entries(AuditOperation.PAYMENT_REGISTERED).size());
        assertEquals(1, entries(AuditOperation.PAYMENT_REVERSED).size());
        assertTrue(entries(AuditOperation.PAYMENT_REVERSED).get(0).getOldValue().contains("300,00"));
    }

    @Test
    @DisplayName("Zamknięcie sprawy zapisuje przyczynę i zmianę stanu")
    void caseClosureIsAudited() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "brak majątku");

        List<AuditEntry> logged = entries(AuditOperation.CASE_CLOSED);
        assertEquals(1, logged.size());

        AuditEntry entry = logged.get(0);
        assertEquals(AuditEntity.DEBT, entry.getEntityType());
        assertEquals(debtId, entry.getEntityId());
        assertEquals("Aktywna", entry.getOldValue());
        assertEquals("Zamknięta — umorzona", entry.getNewValue());
        assertEquals("brak majątku", entry.getReason());
    }

    @Test
    @DisplayName("Ponowne otwarcie sprawy jest odnotowane")
    void caseReopeningIsAudited() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_LEGAL, "pozew złożony");
        debtService.reopenCase(debtId);

        assertEquals(1, entries(AuditOperation.CASE_REOPENED).size());
        assertEquals("Aktywna", entries(AuditOperation.CASE_REOPENED).get(0).getNewValue());
    }

    @Test
    @DisplayName("Zmiana stanu deklaracji jest odnotowana z uzasadnieniem")
    void declarationStatusChangeIsAudited() {
        Declaration declaration = declarationService.save(Declaration.builder()
                .debtorId(debtorId)
                .debtId(debtId)
                .promisedDate(LocalDate.now().plusDays(5))
                .promisedAmount(new BigDecimal("300.00"))
                .build());

        declarationService.markAsBroken(declaration.getId(), "nie zastano w domu");

        List<AuditEntry> logged = entries(AuditOperation.DECLARATION_STATUS);
        assertEquals(1, logged.size());
        assertEquals("Oczekująca", logged.get(0).getOldValue());
        assertEquals("Złamana", logged.get(0).getNewValue());
        assertEquals("nie zastano w domu", logged.get(0).getReason());
    }

    @Test
    @DisplayName("Historia dłużnika łączy zdarzenia ze spraw, deklaracji i wpłat")
    void debtorHistoryAggregatesAcrossEntities() {
        Declaration declaration = declarationService.save(Declaration.builder()
                .debtorId(debtorId)
                .debtId(debtId)
                .promisedDate(LocalDate.now().plusDays(5))
                .promisedAmount(new BigDecimal("300.00"))
                .build());
        pay("300.00", declaration.getId());
        debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "reszta umorzona");

        List<AuditEntry> history = auditService.findByDebtorId(debtorId);

        assertTrue(history.size() >= 3, "Historia powinna objąć wpłatę, deklarację i sprawę");
        assertTrue(history.stream().anyMatch(e -> e.getEntityType() == AuditEntity.PAYMENT));
        assertTrue(history.stream().anyMatch(e -> e.getEntityType() == AuditEntity.DECLARATION));
        assertTrue(history.stream().anyMatch(e -> e.getEntityType() == AuditEntity.DEBT));
    }

    @Test
    @DisplayName("Ślad po usunięciu dłużnika przeżywa jego usunięcie")
    void debtorDeletionLeavesSurvivingTrail() {
        debtorService.delete(debtorId);

        assertTrue(debtorService.findById(debtorId).isEmpty(), "Dłużnik faktycznie usunięty");

        List<AuditEntry> history = auditService.findByDebtorId(debtorId);
        assertFalse(history.isEmpty(),
                "Brak klucza obcego w audit_log jest celowy — kaskada nie może skasować śladu");

        AuditEntry deletion = history.stream()
                .filter(entry -> entry.getOperation() == AuditOperation.DELETE)
                .findFirst().orElseThrow();
        assertEquals(AuditEntity.DEBTOR, deletion.getEntityType());
        assertTrue(deletion.getOldValue().contains("Jan Kowalski"));
        assertTrue(deletion.getOldValue().contains("83120102021"), "PESEL pozwala zidentyfikować sprawę");
    }

    @Test
    @DisplayName("Usunięcie sprawy jest odnotowane wraz z jej opisem")
    void debtDeletionIsAudited() {
        debtService.delete(debtId);

        AuditEntry entry = entries(AuditOperation.DELETE).get(0);
        assertEquals(AuditEntity.DEBT, entry.getEntityType());
        assertTrue(entry.getOldValue().contains("SPR/2017/001"));
        assertTrue(entry.getOldValue().contains("ALIOR"));
    }

    @Test
    @DisplayName("Zmiana bez różnicy nie tworzy wpisu")
    void unchangedValueIsNotRecorded() {
        auditService.recordChange(AuditEntity.DEBT, debtId, debtorId,
                AuditOperation.UPDATE, "Wierzyciel", "ALIOR", "ALIOR", null);
        auditService.recordChange(AuditEntity.DEBT, debtId, debtorId,
                AuditOperation.UPDATE, "Opis", null, "", null);

        assertTrue(entries(AuditOperation.UPDATE).isEmpty());

        auditService.recordChange(AuditEntity.DEBT, debtId, debtorId,
                AuditOperation.UPDATE, "Wierzyciel", "ALIOR", "BPH", null);
        assertEquals(1, entries(AuditOperation.UPDATE).size());
    }

    @Test
    @DisplayName("Wariant bez zapisu niczego nie odnotowuje i nie przeszkadza")
    void noOpAuditServiceStaysSilent() {
        AuditService disabled = AuditService.noOp();

        assertFalse(disabled.isEnabled());
        disabled.record(AuditEntity.DEBT, debtId, debtorId, AuditOperation.DELETE, "test");

        assertTrue(disabled.findByDebtorId(debtorId).isEmpty());
        assertTrue(entries(AuditOperation.DELETE).isEmpty(),
                "Wariant bez zapisu nie może dopisać niczego do prawdziwego dziennika");
    }
}
