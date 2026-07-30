package com.collectorstools.demo;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.importer.ImportSummary;
import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.AuditLogRepository;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorPhoneRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.repository.DeclarationRepository;
import com.collectorstools.repository.InteractionRepository;
import com.collectorstools.repository.PaymentRepository;
import com.collectorstools.service.AuditService;
import com.collectorstools.service.CaseImportService;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy wgrywania danych demonstracyjnych.
 *
 * <p>Loader idzie tą samą ścieżką co import pliku od wierzyciela, więc ten test jest
 * zarazem najszerszym testem samego importu: 171 wierszy przechodzi przez czytnik,
 * parser komentarza, walidację i zapis w transakcji.
 */
class DemoDataLoaderTest {

    private Connection conn;
    private DebtorRepository debtorRepository;
    private DebtRepository debtRepository;
    private DemoDataLoader loader;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        debtorRepository = new DebtorRepository(conn);
        debtRepository = new DebtRepository(conn);
        CaseImportService importService = new CaseImportService(
                debtorRepository, new DebtorPhoneRepository(conn), debtRepository,
                new DeclarationRepository(conn), new InteractionRepository(conn),
                new PaymentRepository(conn),
                new TransactionManager(conn), new AuditService(new AuditLogRepository(conn)));
        loader = new DemoDataLoader(importService);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    @DisplayName("Wgrywa cały plik demonstracyjny bez pominięć")
    void loadsEveryCase() throws Exception {
        ImportSummary summary = loader.load();

        assertEquals(171, summary.getRowsImported(), summary.getFailures().toString());
        assertEquals(0, summary.getRowsSkipped());
        assertTrue(summary.getFailures().isEmpty(), summary.getFailures().toString());

        assertEquals(171, allDebts().size());
        assertTrue(summary.getDeclarationsCreated() >= 60,
                "Deklaracji z obietnic: " + summary.getDeclarationsCreated());
        assertTrue(summary.getPaymentsRecorded() >= 150,
                "Wpłat: " + summary.getPaymentsRecorded());
    }

    /**
     * 16 osób miało w portfelu po kilka spraw. Gdyby import zakładał dłużnika na każdy
     * wiersz, dane demo pokazywałyby 171 osobnych osób zamiast relacji, na której stoi
     * cała aplikacja.
     */
    @Test
    @DisplayName("Osoby z kilkoma sprawami zostają jednym dłużnikiem")
    void reusesDebtorsAcrossCases() throws Exception {
        ImportSummary summary = loader.load();

        List<Debtor> debtors = debtorRepository.findAll();
        assertTrue(debtors.size() < 171, "Dłużników: " + debtors.size());
        assertEquals(debtors.size(), summary.getDebtorsCreated());
        assertTrue(summary.getDebtorsReused() > 0,
                "Żaden dłużnik nie został użyty ponownie — powtórzenia PESEL-i zniknęły");

        long withSeveralCases = debtors.stream()
                .filter(debtor -> countCases(debtor) > 1)
                .count();
        assertTrue(withSeveralCases >= 10, "Dłużników z kilkoma sprawami: " + withSeveralCases);
    }

    /**
     * Wgranie po raz drugi to sytuacja, którą użytkownik wyprodukuje sam. Sprawy mają się
     * podwoić, dłużnicy nie — rozpoznaje ich PESEL.
     *
     * <p>Wyjątkiem są wiersze bez numeru: 7 spraw w portfelu go nie miało, a bez niego nie ma
     * po czym poznać, że to ta sama osoba. Powstają więc drugi raz. Dopasowanie po nazwisku
     * byłoby gorsze niż ten duplikat — sklejałoby ze sobą imienników.
     */
    @Test
    @DisplayName("Powtórne wgranie dopina sprawy, nowi dłużnicy powstają tylko bez numeru PESEL")
    void secondLoadAddsCasesNotPeople() throws Exception {
        loader.load();
        int debtorsAfterFirst = debtorRepository.findAll().size();
        long withoutPesel = debtorRepository.findAll().stream()
                .filter(debtor -> debtor.getPesel() == null || debtor.getPesel().isBlank())
                .count();

        ImportSummary second = loader.load();

        assertEquals(171, second.getRowsImported());
        assertEquals(withoutPesel, second.getDebtorsCreated(),
                "Ponownie powstać mogą wyłącznie osoby, których nie ma po czym rozpoznać");
        assertEquals(debtorsAfterFirst + withoutPesel, debtorRepository.findAll().size());
        assertEquals(342, allDebts().size());
    }

    private long countCases(Debtor debtor) {
        try {
            return debtRepository.findByDebtorId(debtor.getId()).size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sprawy nie mają metody „wszystkie" w repozytorium — liczymy przez dłużników. */
    private List<Debt> allDebts() throws Exception {
        return debtorRepository.findAll().stream()
                .flatMap(debtor -> {
                    try {
                        return debtRepository.findByDebtorId(debtor.getId()).stream();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
