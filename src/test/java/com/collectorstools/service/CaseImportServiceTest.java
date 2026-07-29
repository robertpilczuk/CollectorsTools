package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.importer.*;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import jednego wiersza tworzy komplet powiązanych rekordów w jednej transakcji.
 */
class CaseImportServiceTest {

    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private static final String COMMENT_WITH_PROMISE = "Portfel: DETAL ; "
            + "kategoria sprawy: EGZEKUCJE 2 KAT C; zezwolenie na zawarcie ugody: TAK; "
            + "status kredytu: PO OKRESIE WYPOWIEDZENIA; "
            + "Miejsce zatrudnienia: CENTRUM KULTURY, adres zatrudnienia:LUBLIN,tel. do pracy:815360322;"
            + "data i kwota ostatniej wpłaty: 2016-06-16 81,89; Data wszczęcia egzekucji: 2015-02-17; "
            + "treść notatki: ; treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 50 PLN 31,12";

    @TempDir
    Path directory;

    private Connection conn;
    private CaseImportService importService;
    private DebtorRepository debtorRepository;
    private DebtRepository debtRepository;
    private DeclarationRepository declarationRepository;
    private InteractionRepository interactionRepository;
    private PaymentRepository paymentRepository;
    private DebtorPhoneRepository phoneRepository;
    private PaymentService paymentService;
    private final XlsxReader reader = new XlsxReader();

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        debtorRepository = new DebtorRepository(conn);
        debtRepository = new DebtRepository(conn);
        declarationRepository = new DeclarationRepository(conn);
        interactionRepository = new InteractionRepository(conn);
        paymentRepository = new PaymentRepository(conn);
        phoneRepository = new DebtorPhoneRepository(conn);

        AuditService audit = new AuditService(new AuditLogRepository(conn));
        importService = new CaseImportService(debtorRepository, phoneRepository, debtRepository,
                declarationRepository, interactionRepository, paymentRepository,
                new TransactionManager(conn), audit);
        paymentService = new PaymentService(paymentRepository, debtRepository,
                declarationRepository, new TransactionManager(conn), audit);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private List<ImportRow> rowsFrom(Object[]... rows) throws Exception {
        File file = TestWorkbook.write(directory, "import.xlsx", List.of(rows));
        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
        List<ImportRow> parsed = reader.readRows(file, mapping, EXPORT_DATE);
        importService.markExistingDebtors(parsed);
        return parsed;
    }

    /** Jedyna sprawa w bazie — testy pracują na pojedynczym dłużniku. */
    private Debt onlyDebt() throws Exception {
        return debtRepository.findByDebtorId(debtorRepository.findAll().get(0).getId()).get(0);
    }

    private Object[] fullRow(String caseNumber, String name, String pesel, String comment) {
        return TestWorkbook.row(caseNumber, "tak", name, pesel,
                377.69, 196.40, 181.29, 0.0, "ALIOR", "817442336,662701256", comment);
    }

    @Test
    @DisplayName("Jeden wiersz tworzy dłużnika, telefony, sprawę, kontakt, wpłatę i deklarację")
    void singleRowCreatesAllEntities() throws Exception {
        ImportSummary summary = importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", COMMENT_WITH_PROMISE)),
                "Zlecenie.xlsx");

        assertEquals(1, summary.getRowsImported());
        assertEquals(1, summary.getDebtorsCreated());
        assertEquals(1, summary.getDebtsCreated());
        assertEquals(2, summary.getPhonesCreated());
        assertEquals(1, summary.getInteractionsCreated());
        assertEquals(1, summary.getPaymentsRecorded());
        assertEquals(1, summary.getDeclarationsCreated());

        Debtor debtor = debtorRepository.findAll().get(0);
        assertEquals("URSZULA", debtor.getFirstName());
        assertEquals("CENTRUM KULTURY", debtor.getEmployerName(),
                "Dane o zatrudnieniu pochodzą z komentarza");

        Debt debt = debtRepository.findByDebtorId(debtor.getId()).get(0);
        assertEquals("ALIOR_ZLEC_024", debt.getPortfolio());
        assertEquals("EGZEKUCJE 2 KAT C", debt.getCaseCategory());
        assertEquals(Boolean.TRUE, debt.getSettlementAllowed());
        assertEquals(LocalDate.of(2015, 2, 17), debt.getEnforcementStartDate());
        assertEquals(LocalDate.of(2016, 11, 15), debt.getServiceStartDate());

        assertEquals(ContactType.WIZYTA,
                interactionRepository.findByDebtorId(debtor.getId()).get(0).getContactType());
    }

    @Test
    @DisplayName("Wpłata historyczna nie zmienia salda sprawy")
    void historicalPaymentDoesNotAffectBalance() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", COMMENT_WITH_PROMISE)),
                "Zlecenie.xlsx");

        Debt debt = onlyDebt();
        assertEquals(0, new BigDecimal("377.69").compareTo(debt.getRemainingAmount()),
                "Saldo w eksporcie już uwzględnia tę wpłatę — odjęcie jej byłoby podwójnym księgowaniem");

        Payment payment = paymentRepository.findByDebtId(debt.getId()).get(0);
        assertEquals(Payment.SOURCE_IMPORT, payment.getSource());
        assertEquals(0, new BigDecimal("81.89").compareTo(payment.getAmount()));
    }

    @Test
    @DisplayName("Wpłaty z importu nie można wycofać")
    void importedPaymentCannotBeReversed() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", COMMENT_WITH_PROMISE)),
                "Zlecenie.xlsx");

        Debt debt = onlyDebt();
        long paymentId = paymentRepository.findByDebtId(debt.getId()).get(0).getId();

        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
                () -> paymentService.reversePayment(paymentId));
        assertTrue(thrown.getMessage().contains("importu"));
    }

    @Test
    @DisplayName("Powtórzony PESEL dopina kolejną sprawę do istniejącego dłużnika")
    void duplicatePeselAttachesSecondCase() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", null)), "plik.xlsx");

        List<ImportRow> second = rowsFrom(fullRow("25999", "URSZULA PIŁAT", "83120102021", null));
        assertTrue(second.get(0).isExistingDebtor(), "Podgląd musi pokazać, że dłużnik już istnieje");
        assertTrue(second.get(0).isImportable(), "Powtórzony PESEL nie jest błędem");

        ImportSummary summary = importService.importRows(second, "plik.xlsx");

        assertEquals(0, summary.getDebtorsCreated());
        assertEquals(1, summary.getDebtorsReused());
        assertEquals(1, debtorRepository.findAll().size());
        assertEquals(2, debtRepository.findByDebtorId(debtorRepository.findAll().get(0).getId()).size());
    }

    @Test
    @DisplayName("Numery telefonu nie są duplikowane przy kolejnej sprawie")
    void phonesAreNotDuplicatedForSecondCase() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", null)), "plik.xlsx");
        importService.importRows(
                rowsFrom(fullRow("25999", "URSZULA PIŁAT", "83120102021", null)), "plik.xlsx");

        long debtorId = debtorRepository.findAll().get(0).getId();
        assertEquals(2, phoneRepository.findByDebtorId(debtorId).size());
    }

    @Test
    @DisplayName("Deklaracja powstaje tylko z obietnicy odczytanej pewnie")
    void declarationCreatedOnlyForConfidentPromise() throws Exception {
        String uncertain = "data i kwota ostatniej wpłaty: 2016-06-16 81,89; "
                + "treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY   93,75   PLN   07";

        ImportSummary summary = importService.importRows(
                rowsFrom(fullRow("25576", "JAN KOWALSKI", "83120102021", uncertain)), "plik.xlsx");

        assertEquals(0, summary.getDeclarationsCreated(),
                "Obietnica bez terminu nie może stać się deklaracją z wymyśloną datą");
        assertEquals(1, summary.getPaymentsRecorded(), "Wpłata odczytana pewnie zostaje zapisana");
    }

    @Test
    @DisplayName("Deklaracja z obietnicy respektuje regułę BR-21")
    void importedDeclarationKeepsCreationBeforeDueDate() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", COMMENT_WITH_PROMISE)),
                "plik.xlsx");

        Declaration declaration = declarationRepository
                .findByDebtorId(debtorRepository.findAll().get(0).getId()).get(0);

        assertEquals(LocalDate.of(2016, 12, 31), declaration.getPromisedDate());
        assertEquals(0, new BigDecimal("50").compareTo(declaration.getPromisedAmount()));
        assertFalse(declaration.getPromisedDate().isBefore(declaration.getCreatedAt()),
                "Termin nie może wyprzedzać daty zarejestrowania");
    }

    @Test
    @DisplayName("Zaprzeczona obietnica nie tworzy deklaracji")
    void negatedPromiseCreatesNoDeclaration() throws Exception {
        String negated = "treść ostatniego kontaktu z klientem: OBIETNICY SPŁATY NIE MA, BRAK SRODKOW";

        ImportSummary summary = importService.importRows(
                rowsFrom(fullRow("25576", "JAN KOWALSKI", "83120102021", negated)), "plik.xlsx");

        assertEquals(0, summary.getDeclarationsCreated());
    }

    @Test
    @DisplayName("Wiersze odrzucone przy odczycie są pomijane, reszta zapisuje się normalnie")
    void rejectedRowsAreSkippedWithoutBlockingOthers() throws Exception {
        List<ImportRow> rows = rowsFrom(
                fullRow("25576", "URSZULA PIŁAT", "83120102021", null),
                TestWorkbook.row("25999", "nie", "JAN KOWALSKI", "12345678901",
                        100.0, 100.0, 0.0, 0.0, "ALIOR", null, null),
                fullRow("26000", "ANNA NOWAK", "58041612063", null));

        ImportSummary summary = importService.importRows(rows, "plik.xlsx");

        assertEquals(2, summary.getRowsImported());
        assertEquals(1, summary.getRowsSkipped());
        assertEquals(2, debtorRepository.findAll().size());
    }

    @Test
    @DisplayName("Sprawa ze spłaconym saldem importuje się jako zamknięta")
    void zeroBalanceCaseIsImportedAsClosed() throws Exception {
        Object[] settled = TestWorkbook.row("25576", "nie", "JAN KOWALSKI", "83120102021",
                0.0, 0.0, 0.0, 0.0, "ALIOR", null, null);

        importService.importRows(rowsFrom(settled), "plik.xlsx");

        assertEquals(DebtStatus.CLOSED_PAID, onlyDebt().getStatus());
    }

    @Test
    @DisplayName("Import zostawia ślad w dzienniku audytowym")
    void importIsAudited() throws Exception {
        importService.importRows(
                rowsFrom(fullRow("25576", "URSZULA PIŁAT", "83120102021", null)),
                "Zlecenie - Lubelskie.xlsx");

        AuditService audit = new AuditService(new AuditLogRepository(conn));
        long debtorId = debtorRepository.findAll().get(0).getId();

        assertTrue(audit.findByDebtorId(debtorId).stream()
                        .anyMatch(entry -> entry.getOperation() == AuditOperation.CREATE
                                && entry.getReason().contains("Zlecenie - Lubelskie.xlsx")),
                "Z dziennika musi wynikać, że sprawa pochodzi z importu i z jakiego pliku");
    }
}
