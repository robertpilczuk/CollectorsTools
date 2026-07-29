package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.collectorstools.ui.FxTestSupport.onFxThread;
import static com.collectorstools.ui.FxTestSupport.startToolkit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialog zamknięcia sprawy egzekwuje reguły BR-13 i BR-14 zanim użytkownik
 * kliknie przycisk — niedostępna opcja znika z listy, a brakujące uzasadnienie
 * blokuje zatwierdzenie.
 */
class CaseClosureDialogTest {

    @BeforeAll
    static void initToolkit() throws Exception {
        startToolkit();
    }

    private Debt debtWithBalance(String remaining) {
        return Debt.builder()
                .id(1L)
                .debtorId(1L)
                .caseNumber("SPR/2017/001")
                .creditor("ALIOR")
                .totalAmount(new BigDecimal("1000.00"))
                .remainingAmount(new BigDecimal(remaining))
                .status(DebtStatus.ACTIVE)
                .build();
    }

    private static ButtonType closeButton(CaseClosureDialog dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .findFirst().orElseThrow();
    }

    private static Node closeNode(CaseClosureDialog dialog) {
        return dialog.getDialogPane().lookupButton(closeButton(dialog));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<DebtStatus> statusCombo(CaseClosureDialog dialog) {
        return (ComboBox<DebtStatus>) dialog.getDialogPane().lookup("#closureStatus");
    }

    private static TextArea reasonArea(CaseClosureDialog dialog) {
        return (TextArea) dialog.getDialogPane().lookup("#closureReason");
    }

    @Test
    @DisplayName("Przy niespłaconym saldzie nie da się wybrać zamknięcia jako spłacona")
    void repaidOptionHiddenWhenBalanceRemains() throws Exception {
        List<DebtStatus> options = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("377.69"));
            return List.copyOf(statusCombo(dialog).getItems());
        });

        assertFalse(options.contains(DebtStatus.CLOSED_PAID),
                "Opcja niemożliwa do zatwierdzenia nie powinna być w ogóle pokazywana");
        assertTrue(options.contains(DebtStatus.CLOSED_WRITTEN_OFF));
        assertTrue(options.contains(DebtStatus.CLOSED_TIME_BARRED));
        assertTrue(options.contains(DebtStatus.CLOSED_LEGAL));
        assertFalse(options.contains(DebtStatus.ACTIVE), "Stan aktywny nie jest zamknięciem");
    }

    @Test
    @DisplayName("Przy zerowym saldzie zamknięcie jako spłacona jest dostępne")
    void repaidOptionAvailableWhenBalanceIsZero() throws Exception {
        List<DebtStatus> options = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("0.00"));
            return List.copyOf(statusCombo(dialog).getItems());
        });

        assertTrue(options.contains(DebtStatus.CLOSED_PAID));
        assertEquals(4, options.size());
    }

    @Test
    @DisplayName("Zamknięcie z niespłaconym saldem wymaga uzasadnienia")
    void reasonRequiredWhenBalanceRemains() throws Exception {
        Boolean[] states = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("377.69"));
            statusCombo(dialog).setValue(DebtStatus.CLOSED_WRITTEN_OFF);
            boolean disabledWithoutReason = closeNode(dialog).isDisabled();

            reasonArea(dialog).setText("   ");
            boolean disabledWithBlankReason = closeNode(dialog).isDisabled();

            reasonArea(dialog).setText("dłużnik nie posiada majątku");
            boolean disabledWithReason = closeNode(dialog).isDisabled();

            return new Boolean[]{disabledWithoutReason, disabledWithBlankReason, disabledWithReason};
        });

        assertTrue(states[0], "Bez uzasadnienia zapis musi być zablokowany");
        assertTrue(states[1], "Sam biały znak to nie uzasadnienie");
        assertFalse(states[2]);
    }

    @Test
    @DisplayName("Przy zerowym saldzie uzasadnienie jest opcjonalne")
    void reasonOptionalWhenBalanceIsZero() throws Exception {
        boolean disabled = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("0.00"));
            statusCombo(dialog).setValue(DebtStatus.CLOSED_PAID);
            return closeNode(dialog).isDisabled();
        });

        assertFalse(disabled);
    }

    @Test
    @DisplayName("Bez wybranego stanu nie da się zatwierdzić")
    void statusMustBeChosen() throws Exception {
        boolean disabled = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("0.00"));
            reasonArea(dialog).setText("cokolwiek");
            return closeNode(dialog).isDisabled();
        });

        assertTrue(disabled);
    }

    @Test
    @DisplayName("Wynik zawiera wybrany stan i przycięte uzasadnienie")
    void resultCarriesStatusAndTrimmedReason() throws Exception {
        CaseClosureDialog.Result result = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("377.69"));
            statusCombo(dialog).setValue(DebtStatus.CLOSED_TIME_BARRED);
            reasonArea(dialog).setText("  roszczenie przedawnione w 2023  ");
            return dialog.getResultConverter().call(closeButton(dialog));
        });

        assertEquals(DebtStatus.CLOSED_TIME_BARRED, result.status());
        assertEquals("roszczenie przedawnione w 2023", result.reason());
    }

    @Test
    @DisplayName("Puste uzasadnienie zwracane jest jako brak wartości")
    void blankReasonBecomesNull() throws Exception {
        CaseClosureDialog.Result result = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("0.00"));
            statusCombo(dialog).setValue(DebtStatus.CLOSED_PAID);
            return dialog.getResultConverter().call(closeButton(dialog));
        });

        assertEquals(DebtStatus.CLOSED_PAID, result.status());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("Anulowanie nie zwraca wyniku")
    void cancellingReturnsNothing() throws Exception {
        CaseClosureDialog.Result result = onFxThread(() -> {
            CaseClosureDialog dialog = new CaseClosureDialog(debtWithBalance("100.00"));
            return dialog.getResultConverter().call(ButtonType.CANCEL);
        });

        assertNull(result);
    }
}
