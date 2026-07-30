package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorAuditRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.service.DebtService;
import com.collectorstools.service.DebtorAuditService;
import com.collectorstools.service.DebtorService;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;

import static com.collectorstools.ui.FxTestSupport.onFxThread;
import static com.collectorstools.ui.FxTestSupport.startToolkit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy panelu ze sprawami dłużnika pokazywanego przy imporcie.
 *
 * <p>Powtórzony PESEL przestał być błędem, więc użytkownik decyduje sam — a decyduje
 * na podstawie tego, co panel pokaże. Milczący albo mylący panel jest tu gorszy
 * niż dawne odrzucenie wiersza, bo prowadzi do zapisu, którego nikt nie chciał.
 */
class ExistingCasesPaneTest {

    private Connection conn;
    private DebtorRepository debtorRepository;
    private DebtRepository debtRepository;
    private ExistingCasesPane pane;

    @BeforeAll
    static void initToolkit() throws Exception {
        startToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        debtorRepository = new DebtorRepository(conn);
        debtRepository = new DebtRepository(conn);

        DebtorService debtorService = new DebtorService(
                debtorRepository, new DebtorAuditService(new DebtorAuditRepository(conn)));
        DebtService debtService = new DebtService(debtRepository);
        pane = onFxThread(() -> new ExistingCasesPane(debtorService, debtService));
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private long saveDebtor(String pesel) throws Exception {
        return debtorRepository.save(Debtor.builder()
                .firstName("Jan").lastName("Kowalski").pesel(pesel).build()).getId();
    }

    private void saveDebt(long debtorId, String caseNumber, String remaining) throws Exception {
        debtRepository.save(Debt.builder()
                .debtorId(debtorId)
                .caseNumber(caseNumber)
                .creditor("ALIOR")
                .totalAmount(new BigDecimal(remaining))
                .remainingAmount(new BigDecimal(remaining))
                .build());
    }

    @Test
    void listsAllCasesOfKnownDebtor() throws Exception {
        long debtorId = saveDebtor("85010112345");
        saveDebt(debtorId, "SPR/1", "300.00");
        saveDebt(debtorId, "SPR/2", "200.50");

        onFxThread(() -> pane.showFor("85010112345"));

        assertEquals(2, pane.cases.size());
        assertTrue(pane.header.getText().contains("Jan Kowalski"), pane.header.getText());
        assertTrue(pane.header.getText().contains("500,50"), pane.header.getText());
    }

    @Test
    void saysNewDebtorWhenPeselIsUnknown() throws Exception {
        saveDebtor("85010112345");

        onFxThread(() -> pane.showFor("99031712345"));

        assertTrue(pane.cases.isEmpty());
        assertTrue(pane.header.getText().contains("powstanie nowy dłużnik"), pane.header.getText());
    }

    /**
     * Bez numeru PESEL nie da się rozpoznać osoby — panel musi to powiedzieć wprost,
     * zamiast sugerować, że sprawdził bazę i nic nie znalazł.
     */
    @Test
    void saysNothingCanBeCheckedWithoutPesel() throws Exception {
        onFxThread(() -> pane.showFor(null));
        assertTrue(pane.header.getText().contains("nie ma numeru PESEL"), pane.header.getText());

        onFxThread(() -> pane.showFor("  "));
        assertTrue(pane.header.getText().contains("nie ma numeru PESEL"), pane.header.getText());
    }

    @Test
    void showsNoteOnlyWhenSamePeselRepeatsInFile() throws Exception {
        saveDebtor("85010112345");

        onFxThread(() -> pane.showFor("85010112345", 0));
        assertFalse(pane.fileNote.isVisible());

        onFxThread(() -> pane.showFor("85010112345", 2));
        assertTrue(pane.fileNote.isVisible());
        assertTrue(pane.fileNote.getText().contains("2"), pane.fileNote.getText());
    }

    @Test
    void clearResetsHeaderNoteAndCases() throws Exception {
        long debtorId = saveDebtor("85010112345");
        saveDebt(debtorId, "SPR/1", "300.00");

        onFxThread(() -> pane.showFor("85010112345", 1));
        onFxThread(() -> pane.clear());

        assertTrue(pane.cases.isEmpty());
        assertFalse(pane.fileNote.isVisible());
        assertTrue(pane.header.getText().contains("Zaznacz wiersz"), pane.header.getText());
    }
}
