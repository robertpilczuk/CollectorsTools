package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.collectorstools.ui.FxTestSupport.onFxThread;
import static com.collectorstools.ui.FxTestSupport.startToolkit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy formularza sprawy. Powód powstania: edycja sprawy kasowała wszystkie pola,
 * których formularz nie pokazuje — łącznie ze stanem zamknięcia. Błąd wyszedł
 * z przeglądu kodu, nie z testu, i nic nie chroniło przed jego powrotem.
 */
class DebtFormDialogTest {

    @BeforeAll
    static void initToolkit() throws Exception {
        startToolkit();
    }

    private Debt fullDebt() {
        return Debt.builder()
                .id(7L)
                .debtorId(1L)
                .caseNumber("SPR/2017/001")
                .creditor("ALIOR")
                .originalCreditor("ALIOR BANK SA")
                .portfolio("ALIOR_ZLEC_024")
                .product("KREDYT KONSOLIDACYJNY GEKON")
                .bankAccount("55171000070000001674070150")
                .totalAmount(new BigDecimal("1000.00"))
                .remainingAmount(new BigDecimal("377.69"))
                .principalAmount(new BigDecimal("196.40"))
                .interestAmount(new BigDecimal("181.29"))
                .costAmount(BigDecimal.ZERO)
                .caseCategory("EGZEKUCJE 2 KAT C")
                .creditStatus("PO OKRESIE WYPOWIEDZENIA")
                .settlementAllowed(true)
                .court("SR Lublin-Zachód")
                .courtSignature("VI Nc-e 123456/16")
                .serviceStartDate(LocalDate.of(2016, 11, 15))
                .enforcementStartDate(LocalDate.of(2015, 2, 17))
                .status(DebtStatus.CLOSED_WRITTEN_OFF)
                .closedAt(LocalDate.of(2026, 7, 1))
                .closeReason("brak majątku")
                .description("stary opis")
                .build();
    }

    private static ButtonType saveButton(DebtFormDialog dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .findFirst().orElseThrow();
    }

    private static Node saveNode(DebtFormDialog dialog) {
        return dialog.getDialogPane().lookupButton(saveButton(dialog));
    }

    private static TextField field(DebtFormDialog dialog, String id) {
        return (TextField) dialog.getDialogPane().lookup("#" + id);
    }

    @Test
    @DisplayName("Edycja zachowuje pola, których formularz nie pokazuje")
    void editingPreservesFieldsOutsideTheForm() throws Exception {
        Debt original = fullDebt();

        Debt result = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, original);
            ((TextArea) dialog.getDialogPane().lookup("#description")).setText("nowy opis");
            return dialog.getResultConverter().call(saveButton(dialog));
        });

        // Pola edytowane
        assertEquals("nowy opis", result.getDescription());

        // Pola spoza formularza — muszą przetrwać zapis
        assertEquals("ALIOR_ZLEC_024", result.getPortfolio());
        assertEquals("KREDYT KONSOLIDACYJNY GEKON", result.getProduct());
        assertEquals("ALIOR BANK SA", result.getOriginalCreditor());
        assertEquals("55171000070000001674070150", result.getBankAccount());
        assertEquals("EGZEKUCJE 2 KAT C", result.getCaseCategory());
        assertEquals("PO OKRESIE WYPOWIEDZENIA", result.getCreditStatus());
        assertEquals(Boolean.TRUE, result.getSettlementAllowed());
        assertEquals("SR Lublin-Zachód", result.getCourt());
        assertEquals("VI Nc-e 123456/16", result.getCourtSignature());
        assertEquals(LocalDate.of(2016, 11, 15), result.getServiceStartDate());
        assertEquals(LocalDate.of(2015, 2, 17), result.getEnforcementStartDate());

        // Stan zamknięcia — korekta opisu nie może otworzyć sprawy z powrotem
        assertEquals(DebtStatus.CLOSED_WRITTEN_OFF, result.getStatus());
        assertEquals(LocalDate.of(2026, 7, 1), result.getClosedAt());
        assertEquals("brak majątku", result.getCloseReason());
    }

    @Test
    @DisplayName("Formularz wypełnia się rozbiciem salda edytowanej sprawy")
    void populatesBreakdownFromExistingDebt() throws Exception {
        String[] values = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, fullDebt());
            return new String[]{
                    field(dialog, "principal").getText(),
                    field(dialog, "interest").getText(),
                    field(dialog, "cost").getText()
            };
        });

        assertEquals("196.40", values[0]);
        assertEquals("181.29", values[1]);
        assertEquals("", values[2], "Zerowy składnik oznacza brak, nie wartość do pokazania");
    }

    @Test
    @DisplayName("Niezgodne rozbicie salda blokuje zapis")
    void mismatchedBreakdownBlocksSaving() throws Exception {
        boolean disabled = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, fullDebt());
            field(dialog, "principal").setText("100.00");
            return saveNode(dialog).isDisabled();
        });

        assertTrue(disabled, "Suma 100 + 181,29 nie równa się kwocie pozostałej 377,69");
    }

    @Test
    @DisplayName("Zgodne rozbicie salda pozwala zapisać i potwierdza to komunikatem")
    void matchingBreakdownAllowsSaving() throws Exception {
        Object[] state = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, fullDebt());
            field(dialog, "principal").setText("200.00");
            field(dialog, "interest").setText("177.69");
            Label hint = (Label) dialog.getDialogPane().lookup("#breakdownHint");
            return new Object[]{saveNode(dialog).isDisabled(), hint.getText()};
        });

        assertFalse((Boolean) state[0]);
        assertTrue(((String) state[1]).contains("zgadza się"), "Komunikat: " + state[1]);
    }

    @Test
    @DisplayName("Puste rozbicie salda jest dopuszczalne")
    void emptyBreakdownIsAllowed() throws Exception {
        Object[] state = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, fullDebt());
            field(dialog, "principal").setText("");
            field(dialog, "interest").setText("");
            field(dialog, "cost").setText("");
            Label hint = (Label) dialog.getDialogPane().lookup("#breakdownHint");
            return new Object[]{saveNode(dialog).isDisabled(), hint.getText()};
        });

        assertFalse((Boolean) state[0], "Windykator może znać wyłącznie kwotę łączną");
        assertTrue(((String) state[1]).contains("opcjonalne"));
    }

    @Test
    @DisplayName("Nowa sprawa startuje z pustym formularzem i zablokowanym zapisem")
    void newCaseStartsEmptyAndBlocked() throws Exception {
        Object[] state = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, null);
            return new Object[]{saveNode(dialog).isDisabled(), field(dialog, "caseNumber").getText()};
        });

        assertTrue((Boolean) state[0]);
        assertEquals("", state[1]);
    }

    @Test
    @DisplayName("Nowa sprawa dostaje identyfikator dłużnika i pusty stan zamknięcia")
    void newCaseCarriesDebtorIdAndNoClosure() throws Exception {
        Debt result = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(42L, null);
            field(dialog, "caseNumber").setText("SPR/NOWA");
            field(dialog, "creditor").setText("BPH");
            field(dialog, "totalAmount").setText("500");
            field(dialog, "remaining").setText("500");
            return dialog.getResultConverter().call(saveButton(dialog));
        });

        assertEquals(42L, result.getDebtorId());
        assertNull(result.getStatus(), "Stan nadaje serwis przy zapisie");
        assertNull(result.getClosedAt());
    }

    @Test
    @DisplayName("Anulowanie nie zwraca wyniku")
    void cancellingReturnsNothing() throws Exception {
        Debt result = onFxThread(() -> {
            DebtFormDialog dialog = new DebtFormDialog(1L, fullDebt());
            return dialog.getResultConverter().call(ButtonType.CANCEL);
        });

        assertNull(result);
    }
}
