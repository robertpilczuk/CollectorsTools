package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.importer.HeaderMapping;
import com.collectorstools.importer.ImportRow;
import com.collectorstools.importer.ImportSummary;
import com.collectorstools.importer.TestWorkbook;
import com.collectorstools.importer.XlsxReader;
import com.collectorstools.model.*;
import com.collectorstools.repository.*;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dwa pełne przepływy przez wszystkie warstwy naraz.
 *
 * <p>Testy jednostkowe pilnują pojedynczych reguł i każdy z nich może przechodzić, gdy
 * całość i tak nie działa — bo serwisy przekazują sobie stan, którego żaden z nich nie
 * ogląda w całości. Te dwa przepływy odtwarzają drogę sprawy tak, jak przechodzi ją
 * użytkownik: od pliku wierzyciela do zamknięcia i od kontaktu do umorzenia.
 */
class EndToEndFlowTest {

    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    /** Wiersz eksportu z obietnicą spłaty, ostatnią wpłatą i odbytą wizytą terenową. */
    private static final String COMMENT = "Portfel: DETAL; kategoria sprawy: EGZEKUCJE 2 KAT C; "
            + "zezwolenie na zawarcie ugody: TAK; status kredytu: PO OKRESIE WYPOWIEDZENIA; "
            + "Miejsce zatrudnienia: CENTRUM KULTURY, adres zatrudnienia: LUBLIN, tel. do pracy: 815360322; "
            + "data i kwota ostatniej wpłaty: 2016-06-16 81,89; Data wszczęcia egzekucji: 2015-02-17; "
            + "treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 50 PLN 31,12";

    @TempDir
    Path directory;

    private Connection conn;

    private DebtorService debtorService;
    private DebtService debtService;
    private DeclarationService declarationService;
    private PaymentService paymentService;
    private InteractionService interactionService;
    private CaseImportService importService;
    private AuditService auditService;

    private DebtorPhoneRepository phoneRepository;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();

        DebtorRepository debtorRepository = new DebtorRepository(conn);
        DebtRepository debtRepository = new DebtRepository(conn);
        DeclarationRepository declarationRepository = new DeclarationRepository(conn);
        InteractionRepository interactionRepository = new InteractionRepository(conn);
        PaymentRepository paymentRepository = new PaymentRepository(conn);
        phoneRepository = new DebtorPhoneRepository(conn);

        auditService = new AuditService(new AuditLogRepository(conn));
        debtorService = new DebtorService(debtorRepository,
                new DebtorAuditService(new DebtorAuditRepository(conn)), auditService);
        debtService = new DebtService(debtRepository, auditService);
        declarationService = new DeclarationService(declarationRepository, auditService);
        interactionService = new InteractionService(interactionRepository);
        paymentService = new PaymentService(paymentRepository, debtRepository,
                declarationRepository, new TransactionManager(conn), auditService);
        importService = new CaseImportService(debtorRepository, phoneRepository, debtRepository,
                declarationRepository, interactionRepository, paymentRepository,
                new TransactionManager(conn), auditService);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    // ===== przepływ 1: plik wierzyciela → spłata → zamknięcie =====

    @Test
    @DisplayName("Przepływ: import sprawy, spłata w dwóch ratach, automatyczne zamknięcie")
    void importedCaseIsPaidOffAndClosesItself() throws Exception {
        ImportSummary summary = importOneCase();
        assertEquals(1, summary.getRowsImported(), summary.getFailures().toString());

        Debtor debtor = debtorService.findAll().get(0);
        Debt debt = debtService.findByDebtorId(debtor.getId()).get(0);
        Declaration declaration = declarationService.findByDebtorId(debtor.getId()).get(0);

        // Import odtwarza całą sprawę: dane osoby, telefony, kontakt terenowy i historię
        assertEquals("URSZULA", debtor.getFirstName());
        assertEquals(2, phoneRepository.findByDebtorId(debtor.getId()).size());
        assertEquals(1, interactionService.countByDebtorId(debtor.getId()));
        assertEquals(ContactType.WIZYTA,
                interactionService.findByDebtorId(debtor.getId()).get(0).getContactType());
        assertEquals(DebtStatus.ACTIVE, debt.getStatus());
        assertEquals(DeclarationStatus.PENDING, declaration.getStatus());
        assertEquals(0, new BigDecimal("50").compareTo(declaration.getPromisedAmount()));

        // Wpłata historyczna z eksportu jest śladem, a nie operacją — saldo już ją uwzględnia
        assertEquals(1, paymentService.countByDebtId(debt.getId()));
        assertEquals(0, new BigDecimal("377.69").compareTo(debt.getRemainingAmount()));

        // Rata pierwsza: wysokość obietnicy, w terminie
        paymentService.registerPayment(payment(debt.getId(), declaration.getId(), "50.00",
                declaration.getPromisedDate()));

        Debt afterFirst = debtService.findById(debt.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("327.69").compareTo(afterFirst.getRemainingAmount()));
        // Zaliczenie idzie na koszty, potem odsetki (art. 451 § 1 KC); kosztów tu nie ma
        assertEquals(0, new BigDecimal("131.29").compareTo(afterFirst.getInterestAmount()));
        assertEquals(0, new BigDecimal("196.40").compareTo(afterFirst.getPrincipalAmount()));
        assertEquals(DeclarationStatus.FULFILLED,
                declarationService.findById(declaration.getId()).orElseThrow().getStatus());
        assertEquals(DebtStatus.ACTIVE, afterFirst.getStatus());

        // Rata druga: reszta salda — sprawa zamyka się sama, bez decyzji użytkownika
        paymentService.registerPayment(payment(debt.getId(), null, "327.69", LocalDate.now()));

        Debt closed = debtService.findById(debt.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(closed.getRemainingAmount()));
        assertEquals(DebtStatus.CLOSED_PAID, closed.getStatus());
        assertNotNull(closed.getClosedAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(closed.getComponentsSum()),
                "Po spłacie całości rozbicie salda musi zejść do zera razem z kwotą pozostałą");

        // Ślad audytowy obejmuje obie wpłaty i zamknięcie sprawy
        List<AuditEntry> trail = auditService.findByDebtorId(debtor.getId());
        assertTrue(trail.stream().anyMatch(entry -> entry.getEntityType() == AuditEntity.PAYMENT));
        assertTrue(trail.stream().anyMatch(entry -> entry.getEntityType() == AuditEntity.DEBT));
    }

    /**
     * Wpłaty historycznej z importu nie da się wycofać: saldo w eksporcie już ją
     * uwzględniało, więc nigdy go nie pomniejszyła, a wycofanie dodałoby do salda kwotę,
     * której z niego nie odjęto. Reguła ma sens tylko w połączeniu z importem, dlatego
     * sprawdzana jest tutaj, a nie wśród testów samych wpłat.
     */
    @Test
    @DisplayName("Wpłaty przeniesionej z eksportu wierzyciela nie można wycofać")
    void historicalPaymentCannotBeReversed() throws Exception {
        importOneCase();

        Debtor debtor = debtorService.findAll().get(0);
        Debt debt = debtService.findByDebtorId(debtor.getId()).get(0);
        Payment historical = paymentService.findByDebtId(debt.getId()).get(0);

        assertEquals(Payment.SOURCE_IMPORT, historical.getSource());
        assertThrows(RuntimeException.class, () -> paymentService.reversePayment(historical.getId()));

        assertEquals(1, paymentService.countByDebtId(debt.getId()));
        assertEquals(0, new BigDecimal("377.69").compareTo(
                debtService.findById(debt.getId()).orElseThrow().getRemainingAmount()));
    }

    // ===== przepływ 2: kontakt → złamana deklaracja → wpłata po terminie → umorzenie =====

    @Test
    @DisplayName("Przepływ: złamana deklaracja, wpłata po terminie, umorzenie i otwarcie na nowo")
    void brokenDeclarationIsPaidLateAndCaseIsWrittenOff() {
        long debtorId = debtorService.save(Debtor.builder()
                .firstName("Jan").lastName("Kowalski").pesel("83120102021").city("LUBLIN").build()).getId();

        long debtId = debtService.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/002")
                .creditor("BPH")
                .totalAmount(new BigDecimal("1000.00"))
                .remainingAmount(new BigDecimal("1000.00"))
                .principalAmount(new BigDecimal("800.00"))
                .interestAmount(new BigDecimal("150.00"))
                .costAmount(new BigDecimal("50.00"))
                .build()).getId();

        interactionService.save(Interaction.builder()
                .debtorId(debtorId)
                .interactionDate(LocalDate.now().minusDays(10))
                .contactType(ContactType.TELEFON)
                .notes("Klient zadeklarował wpłatę 400 zł")
                .build());

        LocalDate overdue = LocalDate.now().minusDays(3);
        long declarationId = declarationService.save(Declaration.builder()
                .debtorId(debtorId)
                .debtId(debtId)
                .createdAt(LocalDate.now().minusDays(10))
                .promisedDate(overdue)
                .promisedAmount(new BigDecimal("400.00"))
                .status(DeclarationStatus.PENDING)
                .build()).getId();

        // Termin minął — automat oznacza deklarację jako złamaną.
        // Powodu nie zapisuje: łamanie przy starcie aplikacji to zbiorczy UPDATE,
        // a nie decyzja windykatora. Powód niesie dopiero ręczne markAsBroken.
        declarationService.refreshOverdue();
        Declaration broken = declarationService.findById(declarationId).orElseThrow();
        assertEquals(DeclarationStatus.BROKEN, broken.getStatus());
        assertNull(broken.getBrokenReason());

        // Wpłata po terminie: częściowa, więc deklaracja nie jest jeszcze dotrzymana
        paymentService.registerPayment(payment(debtId, declarationId, "100.00", LocalDate.now()));

        Declaration afterPartial = declarationService.findById(declarationId).orElseThrow();
        assertEquals(DeclarationStatus.PARTIALLY_FULFILLED, afterPartial.getStatus());

        Debt afterPartialDebt = debtService.findById(debtId).orElseThrow();
        assertEquals(0, new BigDecimal("900.00").compareTo(afterPartialDebt.getRemainingAmount()));
        // Zaliczenie: najpierw koszty (50), potem odsetki (50 ze 150)
        assertEquals(0, BigDecimal.ZERO.compareTo(afterPartialDebt.getCostAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(afterPartialDebt.getInterestAmount()));
        assertEquals(0, new BigDecimal("800.00").compareTo(afterPartialDebt.getPrincipalAmount()));

        // Wierzyciel odstępuje od dochodzenia reszty — zamknięcie z dodatnim saldem wymaga powodu
        assertThrows(RuntimeException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "  "));

        debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "brak majątku, egzekucja bezskuteczna");

        Debt writtenOff = debtService.findById(debtId).orElseThrow();
        assertEquals(DebtStatus.CLOSED_WRITTEN_OFF, writtenOff.getStatus());
        assertEquals("brak majątku, egzekucja bezskuteczna", writtenOff.getCloseReason());
        assertNotNull(writtenOff.getClosedAt());
        assertEquals(0, new BigDecimal("900.00").compareTo(writtenOff.getRemainingAmount()),
                "Umorzenie nie zeruje salda — dług istnieje nadal, tylko nie jest dochodzony");

        // Sprawy zamkniętej nie da się zamknąć drugi raz
        assertThrows(RuntimeException.class,
                () -> debtService.closeCase(debtId, DebtStatus.CLOSED_WRITTEN_OFF, "ponownie"));

        // Wznowienie kasuje dane zamknięcia i przywraca sprawę do obsługi
        debtService.reopenCase(debtId);
        Debt reopened = debtService.findById(debtId).orElseThrow();
        assertEquals(DebtStatus.ACTIVE, reopened.getStatus());
        assertNull(reopened.getClosedAt());
        assertNull(reopened.getCloseReason());

        // Ślad audytowy ma pamiętać obie decyzje, choć w sprawie nie widać już żadnej z nich
        List<AuditEntry> trail = auditService.findByEntity(AuditEntity.DEBT, debtId);
        assertTrue(trail.size() >= 2, "Wpisy audytowe sprawy: " + trail.size());

        // Historia wpłat przeżywa zamknięcie i wznowienie
        assertEquals(1, paymentService.countByDebtId(debtId));
        assertEquals(0, new BigDecimal("100.00").compareTo(paymentService.sumByDebtorId(debtorId)));
        assertFalse(debtorService.findAll().isEmpty());
    }

    private ImportSummary importOneCase() throws Exception {
        Object[] row = TestWorkbook.row("25576", "tak", "URSZULA PIŁAT", "83120102021",
                377.69, 196.40, 181.29, 0.0, "ALIOR", "817442336,662701256", COMMENT);
        File file = TestWorkbook.write(directory, "zlecenie.xlsx", List.<Object[]>of(row));

        XlsxReader reader = new XlsxReader();
        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
        List<ImportRow> rows = reader.readRows(file, mapping, EXPORT_DATE);
        importService.markExistingDebtors(rows);
        return importService.importRows(rows, "zlecenie.xlsx");
    }

    private Payment payment(long debtId, Long declarationId, String amount, LocalDate date) {
        return Payment.builder()
                .debtId(debtId)
                .declarationId(declarationId)
                .amount(new BigDecimal(amount))
                .paymentDate(date)
                .build();
    }
}
