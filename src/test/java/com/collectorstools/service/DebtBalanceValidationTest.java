package com.collectorstools.service;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DebtorRepository;
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
 * Reguła "saldo = kapitał + odsetki + koszty" oraz nowe pola sprawy
 * pochodzące z eksportu wierzyciela.
 */
class DebtBalanceValidationTest {

    private Connection conn;
    private DebtService service;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        service = new DebtService(new DebtRepository(conn));
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Debt.DebtBuilder base() {
        return Debt.builder()
                .debtorId(debtorId)
                .caseNumber("SPR/2017/001")
                .creditor("ALIOR")
                .totalAmount(new BigDecimal("10000.00"))
                .remainingAmount(new BigDecimal("377.69"));
    }

    @Test
    @DisplayName("Zgodne rozbicie salda przechodzi walidację")
    void acceptsMatchingComponents() {
        Debt saved = service.save(base()
                .principalAmount(new BigDecimal("196.40"))
                .interestAmount(new BigDecimal("181.29"))
                .costAmount(BigDecimal.ZERO)
                .build());

        assertEquals(0, new BigDecimal("377.69").compareTo(
                service.findById(saved.getId()).orElseThrow().getComponentsSum()));
    }

    @Test
    @DisplayName("Niezgodne rozbicie salda jest odrzucane z czytelnym komunikatem")
    void rejectsMismatchedComponents() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.save(base()
                        .principalAmount(new BigDecimal("100.00"))
                        .interestAmount(new BigDecimal("100.00"))
                        .costAmount(BigDecimal.ZERO)
                        .build()));

        assertTrue(thrown.getMessage().contains("377.69"),
                "Komunikat powinien podać oczekiwaną kwotę: " + thrown.getMessage());
    }

    @Test
    @DisplayName("Różnica w granicach grosza jest dopuszczalna")
    void toleratesRoundingWithinOneGrosz() {
        service.save(base()
                .principalAmount(new BigDecimal("196.40"))
                .interestAmount(new BigDecimal("181.30"))
                .costAmount(BigDecimal.ZERO)
                .build());
    }

    @Test
    @DisplayName("Sprawa bez rozbicia salda nadal się zapisuje")
    void allowsDebtWithoutComponentBreakdown() {
        Debt saved = service.save(base().build());

        assertEquals(0, new BigDecimal("377.69").compareTo(
                service.findById(saved.getId()).orElseThrow().getRemainingAmount()));
    }

    @Test
    @DisplayName("Sprawę bez rozbicia salda można zaktualizować po odczycie z bazy")
    void allowsUpdatingDebtWithoutBreakdownAfterReload() {
        // Regresja: kolumny składników są NOT NULL DEFAULT 0, więc sprawa zapisana
        // bez rozbicia wraca z odczytu z zerami. Walidacja rozpoznająca "brak rozbicia"
        // po wartości null uznawała ją za sprawę z rozbiciem 0+0+0 i blokowała
        // każdą aktualizację — w tym zamknięcie sprawy.
        Debt saved = service.save(base().build());

        Debt reloaded = service.findById(saved.getId()).orElseThrow();
        reloaded.setDescription("zmiana opisu");

        service.update(reloaded);

        assertEquals("zmiana opisu",
                service.findById(saved.getId()).orElseThrow().getDescription());
    }

    @Test
    @DisplayName("Ujemny składnik salda jest odrzucany")
    void rejectsNegativeComponent() {
        assertThrows(IllegalArgumentException.class, () -> service.save(base()
                .principalAmount(new BigDecimal("477.69"))
                .interestAmount(new BigDecimal("-100.00"))
                .costAmount(BigDecimal.ZERO)
                .build()));
    }

    @Test
    @DisplayName("Nowe pola sprawy zapisują się i odczytują")
    void persistsCreditorExportFields() {
        Debt saved = service.save(base()
                .originalCreditor("ALIOR BANK SA")
                .portfolio("ALIOR_ZLEC_024")
                .product("KREDYT KONSOLIDACYJNY GEKON")
                .bankAccount("55171000070000001674070150")
                .caseCategory("EGZEKUCJE 2 KAT C")
                .creditStatus("PO OKRESIE WYPOWIEDZENIA")
                .settlementAllowed(true)
                .court("SR Lublin-Zachód")
                .courtSignature("VI Nc-e 123456/16")
                .serviceStartDate(LocalDate.of(2016, 11, 15))
                .enforcementStartDate(LocalDate.of(2015, 2, 17))
                .build());

        Debt found = service.findById(saved.getId()).orElseThrow();
        assertEquals("ALIOR_ZLEC_024", found.getPortfolio());
        assertEquals("KREDYT KONSOLIDACYJNY GEKON", found.getProduct());
        assertEquals("EGZEKUCJE 2 KAT C", found.getCaseCategory());
        assertEquals(Boolean.TRUE, found.getSettlementAllowed());
        assertEquals("VI Nc-e 123456/16", found.getCourtSignature());
        assertEquals(LocalDate.of(2016, 11, 15), found.getServiceStartDate());
        assertEquals(LocalDate.of(2015, 2, 17), found.getEnforcementStartDate());
    }

    @Test
    @DisplayName("Brak zgody na ugodę jest odróżnialny od braku informacji")
    void distinguishesUnknownSettlementFromDenied() {
        Debt unknown = service.save(base().caseNumber("SPR/1").build());
        Debt denied = service.save(base().caseNumber("SPR/2").settlementAllowed(false).build());

        assertNull(service.findById(unknown.getId()).orElseThrow().getSettlementAllowed());
        assertEquals(Boolean.FALSE, service.findById(denied.getId()).orElseThrow().getSettlementAllowed());
    }
}
