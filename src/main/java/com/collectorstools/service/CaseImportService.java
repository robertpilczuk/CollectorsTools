package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.importer.ImportRow;
import com.collectorstools.importer.ImportSummary;
import com.collectorstools.importer.ParseConfidence;
import com.collectorstools.importer.ParsedComment;
import com.collectorstools.importer.PromiseOfPayment;
import com.collectorstools.model.*;
import com.collectorstools.repository.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Zapisuje odczytane wiersze pliku jako komplet powiązanych rekordów.
 *
 * <p>Jeden wiersz eksportu opisuje nawet sześć obiektów: dłużnika, jego numery telefonu,
 * sprawę, kontakt z wizyty, deklarację i wpłatę historyczną. Zapis odbywa się w jednej
 * transakcji na wiersz — połowicznie zaimportowana sprawa, na przykład bez salda albo
 * bez dłużnika, byłaby gorsza od wiersza pominiętego.
 *
 * <p>Błąd pojedynczego wiersza nie przerywa całego importu: wiersz trafia do listy
 * pominiętych z powodem, a pozostałe zapisują się normalnie.
 */
public class CaseImportService {

    private final DebtorRepository debtorRepository;
    private final DebtorPhoneRepository phoneRepository;
    private final DebtRepository debtRepository;
    private final DeclarationRepository declarationRepository;
    private final InteractionRepository interactionRepository;
    private final PaymentRepository paymentRepository;
    private final TransactionManager transactionManager;
    private final AuditService auditService;

    public CaseImportService() {
        this(new DebtorRepository(), new DebtorPhoneRepository(), new DebtRepository(),
                new DeclarationRepository(), new InteractionRepository(), new PaymentRepository(),
                new TransactionManager(), new AuditService());
    }

    public CaseImportService(DebtorRepository debtorRepository,
                             DebtorPhoneRepository phoneRepository,
                             DebtRepository debtRepository,
                             DeclarationRepository declarationRepository,
                             InteractionRepository interactionRepository,
                             PaymentRepository paymentRepository,
                             TransactionManager transactionManager,
                             AuditService auditService) {
        this.debtorRepository = debtorRepository;
        this.phoneRepository = phoneRepository;
        this.debtRepository = debtRepository;
        this.declarationRepository = declarationRepository;
        this.interactionRepository = interactionRepository;
        this.paymentRepository = paymentRepository;
        this.transactionManager = transactionManager;
        this.auditService = auditService;
    }

    /**
     * Oznacza wiersze, których PESEL wskazuje na dłużnika już obecnego w bazie.
     *
     * <p>Wywoływane przed pokazaniem podglądu. Powtórzony PESEL **nie jest błędem** —
     * w analizowanym portfelu 16 osób miało po kilka spraw. Wcześniejsza wersja importu
     * traktowała duplikat jako wiersz do odrzucenia, co wywaliłoby te sprawy z importu.
     */
    public void markExistingDebtors(List<ImportRow> rows) {
        for (ImportRow row : rows) {
            if (row.getPesel() == null) {
                continue;
            }
            findDebtorByPesel(row.getPesel()).ifPresent(existing -> {
                row.setExistingDebtor(true);
                row.warn("Dłużnik już istnieje w bazie — sprawa zostanie do niego dopięta.");
            });
        }
    }

    /**
     * Importuje wskazane wiersze.
     *
     * @param sourceName nazwa pliku źródłowego, odnotowywana w dzienniku audytowym
     */
    public ImportSummary importRows(List<ImportRow> rows, String sourceName) {
        ImportSummary summary = new ImportSummary();

        for (ImportRow row : rows) {
            if (!row.isImportable()) {
                summary.countSkippedRow();
                continue;
            }
            try {
                transactionManager.execute(() -> importRow(row, sourceName, summary));
                summary.countImportedRow();
            } catch (RuntimeException e) {
                summary.addFailure(row.getSourceRowNumber(), e.getMessage());
            }
        }
        return summary;
    }

    private void importRow(ImportRow row, String sourceName, ImportSummary summary) throws SQLException {
        Debtor debtor = findOrCreateDebtor(row, summary);
        importPhones(row, debtor, summary);

        Debt debt = createDebt(row, debtor);
        summary.countDebt();

        importFieldVisit(row, debtor, summary);
        importHistoricalPayment(row, debt, summary);
        importPromise(row, debtor, debt, summary);

        auditService.record(AuditEntity.DEBT, debt.getId(), debtor.getId(),
                AuditOperation.CREATE, "Sprawa", null,
                debt.getCaseNumber() + " — " + debt.getCreditor(),
                "Import z pliku: " + sourceName);
    }

    private Debtor findOrCreateDebtor(ImportRow row, ImportSummary summary) throws SQLException {
        if (row.getPesel() != null) {
            Optional<Debtor> existing = findDebtorByPesel(row.getPesel());
            if (existing.isPresent()) {
                summary.countDebtorReused();
                return existing.get();
            }
        }

        ParsedComment comment = row.getComment();
        Debtor debtor = Debtor.builder()
                .firstName(row.getFirstName())
                .lastName(row.getLastName())
                .pesel(row.getPesel())
                .street(row.getStreetAddress())
                .city(row.getCity())
                .postalCode(row.getPostalCode())
                .secondaryAddress(row.getSecondaryAddress())
                .phone(row.getPhones().isEmpty() ? null : row.getPhones().get(0))
                .employerName(comment != null ? comment.employerName() : null)
                .employerAddress(comment != null ? comment.employerAddress() : null)
                .employerPhone(comment != null ? comment.employerPhone() : null)
                .build();

        debtorRepository.save(debtor);
        summary.countDebtorCreated();
        return debtor;
    }

    private void importPhones(ImportRow row, Debtor debtor, ImportSummary summary) throws SQLException {
        int added = 0;
        boolean first = true;
        for (String number : row.getPhones()) {
            if (!phoneRepository.exists(debtor.getId(), number)) {
                phoneRepository.save(DebtorPhone.builder()
                        .debtorId(debtor.getId())
                        .phoneNumber(number)
                        .primary(first)
                        .source(Payment.SOURCE_IMPORT)
                        .build());
                added++;
            }
            first = false;
        }
        summary.countPhones(added);
    }

    private Debt createDebt(ImportRow row, Debtor debtor) throws SQLException {
        ParsedComment comment = row.getComment();

        // Eksport nie podaje pierwotnej kwoty zobowiązania; kwota kredytu z komentarza
        // jest najbliższym odpowiednikiem, a gdy jej brak — saldo bieżące
        BigDecimal total = comment != null && comment.creditAmount() != null
                && comment.creditAmount().compareTo(row.getBalance()) >= 0
                ? comment.creditAmount()
                : row.getBalance();

        Debt debt = Debt.builder()
                .debtorId(debtor.getId())
                .caseNumber(row.getCaseNumber())
                .creditor(row.getCreditor())
                .originalCreditor(row.getOriginalCreditor())
                .portfolio(row.getPortfolio())
                .product(row.getProduct())
                .bankAccount(row.getBankAccount())
                .court(row.getCourt())
                .courtSignature(row.getCourtSignature())
                .totalAmount(total)
                .remainingAmount(row.getBalance())
                .principalAmount(row.getPrincipal())
                .interestAmount(row.getInterest())
                .costAmount(row.getCosts())
                .serviceStartDate(row.getServiceStartDate())
                .status(row.getBalance().signum() == 0 ? DebtStatus.CLOSED_PAID : DebtStatus.ACTIVE)
                .caseCategory(comment != null ? comment.caseCategory() : null)
                .creditStatus(comment != null ? comment.creditStatus() : null)
                .settlementAllowed(comment != null ? comment.settlementAllowed() : null)
                .enforcementStartDate(comment != null ? comment.enforcementStartDate() : null)
                .build();

        return debtRepository.save(debt);
    }

    private void importFieldVisit(ImportRow row, Debtor debtor, ImportSummary summary) throws SQLException {
        if (!row.isFieldVisit()) {
            return;
        }
        ParsedComment comment = row.getComment();
        String note = row.getVisitNote() != null ? row.getVisitNote()
                : (comment != null ? comment.lastContact() : null);

        interactionRepository.save(Interaction.builder()
                .debtorId(debtor.getId())
                .interactionDate(row.getServiceStartDate() != null
                        ? row.getServiceStartDate() : LocalDate.now())
                .contactType(ContactType.WIZYTA)
                .notes(note)
                .build());
        summary.countInteraction();
    }

    /**
     * Zapisuje ostatnią wpłatę odczytaną z komentarza.
     *
     * <p><b>Wpłata nie zmienia salda.</b> Saldo w eksporcie jest saldem bieżącym, więc
     * uwzględnia już tę wpłatę — przeprowadzenie jej przez {@code PaymentService}
     * odjęłoby kwotę po raz drugi. Rekord powstaje wyłącznie jako ślad historyczny,
     * ze źródłem {@code IMPORT}, i z tego samego powodu nie podlega wycofaniu.
     */
    private void importHistoricalPayment(ImportRow row, Debt debt, ImportSummary summary) throws SQLException {
        ParsedComment comment = row.getComment();
        if (comment == null || !comment.hasLastPayment()) {
            return;
        }
        // Eksport odnotowuje czasem "ostatnią wpłatę" o kwocie zerowej — to zapis
        // techniczny, nie wpłata. Baza słusznie odrzuca taki rekord (CHECK amount > 0),
        // a bez tego sprawdzenia cała sprawa przepadałaby przez wycofanie transakcji.
        if (comment.lastPaymentAmount().signum() <= 0) {
            row.warn("Ostatnia wpłata ma kwotę " + comment.lastPaymentAmount().toPlainString()
                    + " — pominięto, sprawa zaimportowana bez niej.");
            return;
        }

        paymentRepository.save(Payment.builder()
                .debtId(debt.getId())
                .amount(comment.lastPaymentAmount())
                .paymentDate(comment.lastPaymentDate())
                .allocatedCost(BigDecimal.ZERO)
                .allocatedInterest(BigDecimal.ZERO)
                .allocatedPrincipal(BigDecimal.ZERO)
                .note("Ostatnia wpłata odnotowana przez wierzyciela przed przekazaniem sprawy")
                .source(Payment.SOURCE_IMPORT)
                .createdAt(LocalDate.now())
                .userId(CurrentUser.id())
                .build());
        summary.countPayment();
    }

    /**
     * Tworzy deklarację z obietnicy odczytanej z notatki.
     *
     * <p>Obietnice bez czytelnej kwoty albo terminu są pomijane — deklaracja
     * z wymyśloną wartością byłaby gorsza niż jej brak. Wiersz i tak niesie
     * zastrzeżenie parsera, więc użytkownik wie, że coś zostało pominięte.
     */
    private void importPromise(ImportRow row, Debtor debtor, Debt debt, ImportSummary summary)
            throws SQLException {
        ParsedComment comment = row.getComment();
        if (comment == null || !comment.hasPromise()) {
            return;
        }
        PromiseOfPayment promise = comment.promise();
        if (promise.confidence() != ParseConfidence.HIGH || promise.amount() == null
                || !promise.hasDueDate()) {
            return;
        }

        // Reguła BR-21: termin nie może wyprzedzać daty zarejestrowania. Dla danych
        // historycznych obietnica powstała najpóźniej w dniu, na który ją złożono.
        LocalDate createdAt = row.getServiceStartDate() != null
                && !row.getServiceStartDate().isAfter(promise.dueDate())
                ? row.getServiceStartDate()
                : promise.dueDate();

        declarationRepository.save(Declaration.builder()
                .debtorId(debtor.getId())
                .debtId(debt.getId())
                .createdAt(createdAt)
                .promisedDate(promise.dueDate())
                .promisedAmount(promise.amount())
                .status(DeclarationStatus.PENDING)
                .build());
        summary.countDeclaration();
    }

    private Optional<Debtor> findDebtorByPesel(String pesel) {
        try {
            return debtorRepository.findAll().stream()
                    .filter(debtor -> pesel.equals(debtor.getPesel()))
                    .findFirst();
        } catch (SQLException e) {
            throw com.collectorstools.exception.TechnicalException.database("wyszukać dłużnika po numerze PESEL", e);
        }
    }
}
