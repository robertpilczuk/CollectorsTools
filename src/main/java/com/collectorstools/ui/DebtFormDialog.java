package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;

public class DebtFormDialog extends Dialog<Debt> {

    private final TextField  caseNumberField   = new TextField();
    private final TextField  creditorField     = new TextField();
    private final TextField  totalAmountField  = new TextField();
    private final TextField  remainingField    = new TextField();
    private final DatePicker debtDatePicker    = new DatePicker(LocalDate.now());
    private final TextArea   descriptionArea   = new TextArea();

    public DebtFormDialog(Long debtorId, Debt existing) {
        setTitle(existing == null ? "Nowa sprawa" : "Edytuj sprawę");
        setHeaderText(existing == null ? "Wprowadź dane sprawy" : "Edytuj dane sprawy");

        ButtonType saveBtn = new ButtonType("Zapisz", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ColumnConstraints col1 = new ColumnConstraints(140);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        totalAmountField.setPromptText("np. 5000.00");
        remainingField.setPromptText("np. 5000.00");
        descriptionArea.setPromptText("Opis/tytuł długu (opcjonalnie)");
        descriptionArea.setPrefRowCount(3);

        addRow(grid, 0, "Nr sprawy *",     caseNumberField);
        addRow(grid, 1, "Wierzyciel *",    creditorField);
        addRow(grid, 2, "Kwota całk. *",   totalAmountField);
        addRow(grid, 3, "Pozostało *",     remainingField);
        addRow(grid, 4, "Data powstania",  debtDatePicker);
        grid.add(new Separator(), 0, 5, 2, 1);
        grid.add(new Label("Opis"), 0, 6);
        grid.add(descriptionArea, 1, 6);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(520);
        getDialogPane().setMinWidth(520);

        if (existing != null) populate(existing);

        javafx.scene.Node saveNode = getDialogPane().lookupButton(saveBtn);
        saveNode.setDisable(true);
        caseNumberField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        creditorField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        totalAmountField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        remainingField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        if (existing != null) validateForm(saveNode);

        setResultConverter(btn -> {
            if (btn != saveBtn) return null;
            return Debt.builder()
                    .debtorId(debtorId)
                    .caseNumber(caseNumberField.getText().trim())
                    .creditor(creditorField.getText().trim())
                    .totalAmount(parseBigDecimal(totalAmountField.getText()))
                    .remainingAmount(parseBigDecimal(remainingField.getText()))
                    .debtDate(debtDatePicker.getValue())
                    .description(descriptionArea.getText().trim())
                    .build();
        });
    }

    private void addRow(GridPane grid, int row, String label, Control field) {
        Label lbl = new Label(label);
        lbl.setMinWidth(130);
        grid.add(lbl, 0, row);
        grid.add(field, 1, row);
        if (field instanceof TextField tf) tf.setMaxWidth(Double.MAX_VALUE);
    }

    private void populate(Debt d) {
        caseNumberField.setText(d.getCaseNumber());
        creditorField.setText(d.getCreditor());
        totalAmountField.setText(d.getTotalAmount().toPlainString());
        remainingField.setText(d.getRemainingAmount().toPlainString());
        if (d.getDebtDate() != null) debtDatePicker.setValue(d.getDebtDate());
        if (d.getDescription() != null) descriptionArea.setText(d.getDescription());
    }

    private void validateForm(javafx.scene.Node saveNode) {
        boolean valid = !caseNumberField.getText().isBlank()
                && !creditorField.getText().isBlank()
                && isValidAmount(totalAmountField.getText())
                && isValidAmount(remainingField.getText());
        saveNode.setDisable(!valid);
    }

    private boolean isValidAmount(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            return new BigDecimal(text.trim().replace(",", ".")).compareTo(BigDecimal.ZERO) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private BigDecimal parseBigDecimal(String text) {
        return new BigDecimal(text.trim().replace(",", "."));
    }
}
