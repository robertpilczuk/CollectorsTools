package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * Wybór stanu, w jakim sprawa zostaje zamknięta, wraz z uzasadnieniem.
 *
 * <p>Dialog nie pozwala nawet wybrać zamknięcia „spłacona", jeśli sprawa ma niespłacone
 * saldo — zamiast pokazywać błąd po kliknięciu, usuwa niedostępną opcję z listy.
 * Przy dodatnim saldzie uzasadnienie jest obowiązkowe: umorzenie czy przedawnienie
 * to decyzje, za które ktoś odpowiada.
 */
public class CaseClosureDialog extends Dialog<CaseClosureDialog.Result> {

    /** Wynik: stan końcowy i uzasadnienie. */
    public record Result(DebtStatus status, String reason) {
    }

    private final ComboBox<DebtStatus> statusCombo = new ComboBox<>();
    private final TextArea reasonArea = new TextArea();
    private final boolean reasonRequired;

    public CaseClosureDialog(Debt debt) {
        BigDecimal remaining = debt.getRemainingAmount() != null
                ? debt.getRemainingAmount() : BigDecimal.ZERO;
        this.reasonRequired = remaining.compareTo(BigDecimal.ZERO) > 0;

        setTitle("Zamknięcie sprawy");
        setHeaderText(debt.getCaseNumber() + " — " + debt.getCreditor()
                + "\nPozostałe saldo: " + money(remaining) + " zł");

        ButtonType closeButton = new ButtonType("Zamknij sprawę", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(closeButton, ButtonType.CANCEL);

        buildForm(remaining);

        Node okNode = getDialogPane().lookupButton(closeButton);
        okNode.setDisable(true);
        statusCombo.valueProperty().addListener((o, old, value) -> validate(okNode));
        reasonArea.textProperty().addListener((o, old, value) -> validate(okNode));

        setResultConverter(button -> {
            if (button != closeButton) return null;
            String reason = reasonArea.getText() != null ? reasonArea.getText().trim() : "";
            return new Result(statusCombo.getValue(), reason.isEmpty() ? null : reason);
        });
    }

    private void buildForm(BigDecimal remaining) {
        // Sprawy z niespłaconym saldem nie da się zamknąć jako spłaconej —
        // opcja znika z listy, zamiast kończyć się błędem po zatwierdzeniu
        List<DebtStatus> options = Arrays.stream(DebtStatus.values())
                .filter(DebtStatus::isClosed)
                .filter(status -> !status.requiresZeroBalance()
                        || remaining.compareTo(BigDecimal.ZERO) == 0)
                .toList();

        statusCombo.getItems().addAll(options);
        statusCombo.setMaxWidth(Double.MAX_VALUE);
        if (options.size() == 1) {
            statusCombo.setValue(options.get(0));
        }

        reasonArea.setPrefRowCount(3);
        reasonArea.setWrapText(true);
        reasonArea.setPromptText(reasonRequired
                ? "Wymagane — dlaczego sprawa zostaje zamknięta mimo niespłaconego salda?"
                : "Opcjonalnie");

        Label hint = new Label(reasonRequired
                ? "Sprawa ma niespłacone saldo — uzasadnienie jest obowiązkowe."
                : "Saldo spłacone w całości.");
        hint.setStyle(reasonRequired
                ? "-fx-text-fill: #e67e22;" : "-fx-text-fill: #27ae60;");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ColumnConstraints labels = new ColumnConstraints(140);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);

        Label statusLabel = new Label("Przyczyna zamknięcia *");
        statusLabel.setMinWidth(130);
        Label reasonLabel = new Label(reasonRequired ? "Uzasadnienie *" : "Uzasadnienie");
        reasonLabel.setMinWidth(130);

        grid.add(statusLabel, 0, 0);  grid.add(statusCombo, 1, 0);
        grid.add(new Label(""), 0, 1); grid.add(hint, 1, 1);
        grid.add(reasonLabel, 0, 2);  grid.add(reasonArea, 1, 2);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(540);
        getDialogPane().setMinWidth(540);
    }

    private void validate(Node okNode) {
        boolean statusChosen = statusCombo.getValue() != null;
        boolean reasonGiven = reasonArea.getText() != null && !reasonArea.getText().isBlank();
        okNode.setDisable(!statusChosen || (reasonRequired && !reasonGiven));
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value != null ? value : BigDecimal.ZERO;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
