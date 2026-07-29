package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rdzeń procesu: wpłata zmienia saldo sprawy i stan deklaracji, w jednej transakcji.
 */
class PaymentServiceTest {

    private Connection conn;
    private PaymentService paymentService;
    private DebtService debtService;
    private DeclarationService declarationService;
    private long debtorId;
    private long debtId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();

        DebtRepository debtRepository = new DebtRepository(conn);
        DeclarationRepository declarationRepository = new DeclarationRepository(conn);

        paymentService = new PaymentService(
                new PaymentRepository(conn), debtRepository, declarationRepository,
                new TransactionManager(conn));
        debtService = new DebtService(debtRepository);
        declarationService = new DeclarationService(declarationRepository);

        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();

        debtId = debtService.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/001")
                .creditor("ALIOR")
                .totalAmount(new BigDecimal("1000.00"))
                .remainingAmount(new BigDecimal("1000.00"))
                .principalAmount(new BigDecimal("800.00"))
                .interestAmount(new BigDecimal("150.00"))
                .costAmount(new BigDecimal("50.00"))
                .build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Payment payment(String amount, Long declarationId) {
        return Payment.builder()
                .debtId(debtId)
                .declarationId(declarationId)
                .amount(new BigDecimal(amount))
                .paymentDate(LocalDate.now())
                .build();
    }

    private Declaration declaration(String amount, LocalDate date) {
        // Deklaracja z terminem w przeszłości była złożona wcześniej niż dziś
        LocalDate createdAt = date.isBefore(LocalDate.now()) ? date : LocalDate.now();
        return declarationService.save(Declaration.builder()
                .debtorId(debtorId)
                .debtId(debtId)
                .createdAt(createdAt)
                .promisedDate(date)
                .promisedAmount(new BigDecimal(amount))
                .build());
    }

    private BigDecimal remaining() {
        return debtService.findById(debtId).orElseThrow().getRemainingAmount();
    }

    @Test
    @DisplayName("Wpłata obniża saldo sprawy")
    void paymentReducesBalance() {
        paymentService.registerPayment(payment("300.00", null));

        assertEquals(0, new BigDecimal("700.00").compareTo(remaining()));
    }

    @Test
    @DisplayName("Wpłata zaliczana jest kolejno na koszty, odsetki i kapitał")
    void allocatesToCostsThenInterestThenPrincipal() {
        Payment saved = paymentService.registerPayment(payment("120.00", null));

        // 50 kosztów w całości, 70 na odsetki, kapitał nietknięty
        assertEquals(0, new BigDecimal("50.00").compareTo(saved.getAllocatedCost()));
        assertEquals(0, new BigDecimal("70.00").compareTo(saved.getAllocatedInterest()));
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getAllocatedPrincipal()));

        Debt debt = debtService.findById(debtId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(debt.getCostAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(debt.getInterestAmount()));
        assertEquals(0, new BigDecimal("800.00").compareTo(debt.getPrincipalAmount()));
    }

    @Test
    @DisplayName("Po wpłacie rozbicie salda nadal sumuje się do kwoty pozostałej")
    void balanceInvariantHoldsAfterPayment() {
        paymentService.registerPayment(payment("120.00", null));

        Debt debt = debtService.findById(debtId).orElseThrow();
        assertEquals(0, debt.getRemainingAmount().compareTo(debt.getComponentsSum()),
                "saldo musi równać się sumie kapitału, odsetek i kosztów");
    }

    @Test
    @DisplayName("Nadpłata jest odrzucana z podaniem kwoty maksymalnej")
    void rejectsOverpayment() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> paymentService.registerPayment(payment("1200.00", null)));

        assertTrue(thrown.getMessage().contains("1000,00 zł"),
                "Komunikat powinien podać maksymalną możliwą wpłatę w formacie pieniężnym: "
                        + thrown.getMessage());
        assertEquals(0, new BigDecimal("1000.00").compareTo(remaining()),
                "Odrzucona wpłata nie może ruszyć salda");
    }

    @Test
    @DisplayName("Odrzucona wpłata nie zostawia śladu w ewidencji")
    void rejectedPaymentIsNotPersisted() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentService.registerPayment(payment("1200.00", null)));

        assertEquals(0, paymentService.countByDebtId(debtId));
        assertEquals(0, BigDecimal.ZERO.compareTo(paymentService.sumByDebtorId(debtorId)));
    }

    @Test
    @DisplayName("Wpłata pełna oznacza deklarację jako dotrzymaną")
    void fullPaymentFulfillsDeclaration() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));

        paymentService.registerPayment(payment("300.00", declaration.getId()));

        Declaration after = declarationService.findById(declaration.getId()).orElseThrow();
        assertEquals(DeclarationStatus.FULFILLED, after.getStatus());
        assertEquals(LocalDate.now(), after.getPaymentDate());
    }

    @Test
    @DisplayName("Wpłata częściowa oznacza deklarację jako częściowo dotrzymaną")
    void partialPaymentMarksDeclarationPartial() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));

        paymentService.registerPayment(payment("100.00", declaration.getId()));

        assertEquals(DeclarationStatus.PARTIALLY_FULFILLED,
                declarationService.findById(declaration.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Dopłata domyka deklarację częściowo dotrzymaną")
    void toppingUpPartialDeclarationFulfillsIt() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));

        paymentService.registerPayment(payment("100.00", declaration.getId()));
        paymentService.registerPayment(payment("200.00", declaration.getId()));

        assertEquals(DeclarationStatus.FULFILLED,
                declarationService.findById(declaration.getId()).orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("700.00").compareTo(remaining()));
    }

    @Test
    @DisplayName("Wpłata po terminie domyka złamaną deklarację, zachowując powód złamania")
    void latePaymentClosesBrokenDeclaration() {
        Declaration declaration = declaration("300.00", LocalDate.now().minusDays(10));
        declarationService.markAsBroken(declaration.getId(), "nie zastano w domu");

        paymentService.registerPayment(payment("300.00", declaration.getId()));

        Declaration after = declarationService.findById(declaration.getId()).orElseThrow();
        assertEquals(DeclarationStatus.FULFILLED, after.getStatus());
        assertEquals("nie zastano w domu", after.getBrokenReason());
        assertTrue(after.isPaidLate());
    }

    @Test
    @DisplayName("Do anulowanej deklaracji nie można zarejestrować wpłaty")
    void rejectsPaymentForCancelledDeclaration() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));
        declarationService.markAsCancelled(declaration.getId(), "dublet");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.registerPayment(payment("300.00", declaration.getId())));
        assertEquals(0, new BigDecimal("1000.00").compareTo(remaining()));
    }

    @Test
    @DisplayName("Wpłata bez deklaracji jest dozwolona")
    void paymentWithoutDeclarationIsAllowed() {
        Payment saved = paymentService.registerPayment(payment("250.00", null));

        assertNull(saved.getDeclarationId());
        assertEquals(0, new BigDecimal("750.00").compareTo(remaining()));
    }

    @Test
    @DisplayName("Wpłata do nieistniejącej sprawy jest odrzucana")
    void rejectsPaymentForUnknownDebt() {
        Payment orphan = Payment.builder()
                .debtId(9999L)
                .amount(new BigDecimal("100.00"))
                .paymentDate(LocalDate.now())
                .build();

        assertThrows(IllegalArgumentException.class, () -> paymentService.registerPayment(orphan));
    }

    @Test
    @DisplayName("Kwota niedodatnia i brak daty są odrzucane")
    void validatesAmountAndDate() {
        assertThrows(IllegalArgumentException.class, () -> paymentService.registerPayment(payment("0", null)));
        assertThrows(IllegalArgumentException.class, () -> paymentService.registerPayment(payment("-50", null)));
        assertThrows(IllegalArgumentException.class, () -> paymentService.registerPayment(
                Payment.builder().debtId(debtId).amount(new BigDecimal("10")).build()));
    }

    @Test
    @DisplayName("Spłata całości zeruje saldo sprawy")
    void fullRepaymentZeroesBalance() {
        paymentService.registerPayment(payment("1000.00", null));

        Debt debt = debtService.findById(debtId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(debt.getRemainingAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(debt.getComponentsSum()));
    }

    @Test
    @DisplayName("Wycofanie wpłaty przywraca saldo i rozbicie sprzed niej")
    void reversalRestoresBalanceAndComponents() {
        Payment saved = paymentService.registerPayment(payment("120.00", null));

        paymentService.reversePayment(saved.getId());

        Debt debt = debtService.findById(debtId).orElseThrow();
        assertEquals(0, new BigDecimal("1000.00").compareTo(debt.getRemainingAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(debt.getCostAmount()));
        assertEquals(0, new BigDecimal("150.00").compareTo(debt.getInterestAmount()));
        assertEquals(0, new BigDecimal("800.00").compareTo(debt.getPrincipalAmount()));
        assertEquals(0, paymentService.countByDebtId(debtId));
    }

    @Test
    @DisplayName("Wycofanie ostatniej wpłaty cofa deklarację do stanu oczekującej")
    void reversalReturnsDeclarationToPending() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));
        Payment saved = paymentService.registerPayment(payment("300.00", declaration.getId()));

        paymentService.reversePayment(saved.getId());

        Declaration after = declarationService.findById(declaration.getId()).orElseThrow();
        assertEquals(DeclarationStatus.PENDING, after.getStatus());
        assertNull(after.getPaymentDate());
    }

    @Test
    @DisplayName("Wycofanie dopłaty cofa deklarację do stanu częściowego")
    void reversalOfSecondPaymentReturnsDeclarationToPartial() {
        Declaration declaration = declaration("300.00", LocalDate.now().plusDays(5));
        paymentService.registerPayment(payment("100.00", declaration.getId()));
        Payment second = paymentService.registerPayment(payment("200.00", declaration.getId()));

        paymentService.reversePayment(second.getId());

        assertEquals(DeclarationStatus.PARTIALLY_FULFILLED,
                declarationService.findById(declaration.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Sprawa bez rozbicia salda przyjmuje wpłatę bez podziału")
    void handlesDebtWithoutComponentBreakdown() {
        long plainDebtId = debtService.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/002")
                .creditor("BPH")
                .totalAmount(new BigDecimal("500.00"))
                .remainingAmount(new BigDecimal("500.00"))
                .build()).getId();

        Payment saved = paymentService.registerPayment(Payment.builder()
                .debtId(plainDebtId)
                .amount(new BigDecimal("200.00"))
                .paymentDate(LocalDate.now())
                .build());

        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getAllocatedPrincipal()));
        assertEquals(0, new BigDecimal("300.00").compareTo(
                debtService.findById(plainDebtId).orElseThrow().getRemainingAmount()));
    }

    @Test
    @DisplayName("Suma wpłat dłużnika obejmuje wszystkie jego sprawy")
    void sumsPaymentsAcrossDebtorCases() {
        long secondDebtId = debtService.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/002")
                .creditor("BPH")
                .totalAmount(new BigDecimal("500.00"))
                .remainingAmount(new BigDecimal("500.00"))
                .build()).getId();

        paymentService.registerPayment(payment("300.00", null));
        paymentService.registerPayment(Payment.builder()
                .debtId(secondDebtId)
                .amount(new BigDecimal("200.00"))
                .paymentDate(LocalDate.now())
                .build());

        assertEquals(0, new BigDecimal("500.00").compareTo(paymentService.sumByDebtorId(debtorId)));
        assertEquals(2, paymentService.findByDebtorId(debtorId).size());
    }
}
