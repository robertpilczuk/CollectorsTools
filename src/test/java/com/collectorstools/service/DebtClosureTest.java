package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Payment;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.repository.DeclarationRepository;
import com.collectorstools.repository.PaymentRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zamykanie spraw, ochrona historii wpłat i automatyczne domykanie
 * sprawy spłaconej w całości.
 */
class DebtClosureTest {

    private Connection conn;
    private DebtService debtService;
    private PaymentService paymentService;
    private long debtorId;
    private long debtId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        DebtRepository debtRepository = new DebtRepository(conn);
        debtService = new DebtService(debtRepository);
        paymentService = new PaymentService(
                new PaymentRepository(conn), debtRepository,
                new DeclarationRepository(conn), new TransactionManager(conn));

        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();

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

    private Debt debt() {
        return debtService.findById(debtId).orElseThrow();
    }

    private void pay(String amount) {
        paymentService.registerPayment(Payment.builder()
                .debtId(debtId)
                .amount(new BigDecimal(amount))
                .paymentDate(LocalDate.now())
                .build());
    }

    @Test
    @DisplayName("Nowa sprawa jest aktywna")
    void newDebtIsActive() {
        assertEquals(DebtStatus.ACTIVE, debt().getStatus());
        assertFalse(debt().isClosed());
        assertNull(debt().getClosedAt());
    }

    @Test
    @DisplayName("Zamknięcie z dodatnim saldem wymaga przyczyny")
    void closingWithOutstandingBalanceRequiresReason() {
        assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, null));
        assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "   "));

        assertEquals(DebtStatus.ACTIVE, debt().getStatus());
    }

    @Test
    @DisplayName("Zamknięcie z przyczyną zapisuje stan, datę i uzasadnienie")
    void closingWithReasonRecordsEverything() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_TIME_BARRED, "roszczenie przedawnione w 2023");

        Debt closed = debt();
        assertEquals(DebtStatus.CLOSED_TIME_BARRED, closed.getStatus());
        assertEquals(LocalDate.now(), closed.getClosedAt());
        assertEquals("roszczenie przedawnione w 2023", closed.getCloseReason());
        assertTrue(closed.isClosed());
    }

    @Test
    @DisplayName("Sprawy z niespłaconym saldem nie można zamknąć jako spłaconej")
    void cannotCloseAsPaidWithOutstandingBalance() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_PAID, "twierdzi, że zapłacił"));

        assertTrue(thrown.getMessage().contains("spłaconej"));
        assertEquals(DebtStatus.ACTIVE, debt().getStatus());
    }

    @Test
    @DisplayName("Sprawy zamkniętej nie można zamknąć ponownie")
    void cannotCloseTwice() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_LEGAL, "pozew złożony");

        assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "umorzenie"));
    }

    @Test
    @DisplayName("Stan zamknięcia musi być stanem końcowym")
    void closeStatusMustBeTerminal() {
        assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, DebtStatus.ACTIVE, "cokolwiek"));
        assertThrows(IllegalArgumentException.class,
                () -> debtService.closeCase(debtId, null, "cokolwiek"));
    }

    @Test
    @DisplayName("Ponowne otwarcie czyści datę i przyczynę zamknięcia")
    void reopeningClearsClosureData() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "brak majątku");

        debtService.reopenCase(debtId);

        Debt reopened = debt();
        assertEquals(DebtStatus.ACTIVE, reopened.getStatus());
        assertNull(reopened.getClosedAt());
        assertNull(reopened.getCloseReason());
    }

    @Test
    @DisplayName("Sprawy aktywnej nie można otworzyć ponownie")
    void cannotReopenActiveCase() {
        assertThrows(IllegalArgumentException.class, () -> debtService.reopenCase(debtId));
    }

    @Test
    @DisplayName("Spłata całości zamyka sprawę automatycznie jako spłaconą")
    void fullRepaymentClosesCaseAutomatically() {
        pay("1000.00");

        Debt closed = debt();
        assertEquals(DebtStatus.CLOSED_PAID, closed.getStatus());
        assertEquals(LocalDate.now(), closed.getClosedAt());
        assertNull(closed.getCloseReason(), "Spłata nie wymaga uzasadnienia");
    }

    @Test
    @DisplayName("Wpłata częściowa nie zamyka sprawy")
    void partialPaymentLeavesCaseOpen() {
        pay("400.00");

        assertEquals(DebtStatus.ACTIVE, debt().getStatus());
    }

    @Test
    @DisplayName("Wpłata nie nadpisuje zamknięcia z innej przyczyny")
    void paymentDoesNotOverrideManualClosure() {
        debtService.closeCase(debtId, DebtStatus.CLOSED_LEGAL, "sprawa u komornika");

        pay("1000.00");

        assertEquals(DebtStatus.CLOSED_LEGAL, debt().getStatus(),
                "Wpłata domyka saldo, ale nie zmienia decyzji o skierowaniu na drogę sądową");
    }

    @Test
    @DisplayName("Wycofanie wpłaty przywraca sprawę do obsługi")
    void reversingPaymentReopensCase() {
        pay("1000.00");
        assertEquals(DebtStatus.CLOSED_PAID, debt().getStatus());

        long paymentId = paymentService.findByDebtId(debtId).get(0).getId();
        paymentService.reversePayment(paymentId);

        Debt reopened = debt();
        assertEquals(DebtStatus.ACTIVE, reopened.getStatus());
        assertNull(reopened.getClosedAt());
    }

    @Test
    @DisplayName("Sprawy z wpłatami nie można usunąć")
    void cannotDeleteDebtWithPayments() {
        pay("100.00");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> debtService.delete(debtId));

        assertTrue(thrown.getMessage().contains("Zamknij"),
                "Komunikat powinien wskazać alternatywę: " + thrown.getMessage());
        assertTrue(debtService.findById(debtId).isPresent());
    }

    @Test
    @DisplayName("Sprawę bez wpłat można usunąć")
    void canDeleteDebtWithoutPayments() {
        debtService.delete(debtId);

        assertTrue(debtService.findById(debtId).isEmpty());
    }
}
