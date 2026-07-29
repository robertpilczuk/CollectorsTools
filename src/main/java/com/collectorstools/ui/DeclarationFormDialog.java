package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class DeclarationFormDialog extends Dialog<Declaration> {

    private final DatePicker         datePicker = new DatePicker(LocalDate.now().plusWeeks(1));
    private final TextField          amountField = new TextField();
    private final ComboBox<Debt>     debtCombo  = new ComboBox<>();

    public DeclarationFormDialog(Long debtorId, List<Debt> debts) {
        setTitle("Nowa deklaracja");
        setHeaderText("Wprowadź dane deklaracji dłużnika");

        ButtonType saveBtn = new ButtonType("Zapisz", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        amountField.setPromptText("np. 500.00");
        amountField.setMaxWidth(Double.MAX_VALUE);

        debtCombo.getItems().add(null);
        debtCombo.getItems().addAll(debts);
        debtCombo.setPromptText("(opcjonalnie)");
        debtCombo.setMaxWidth(Double.MAX_VALUE);
        debtCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Debt item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "— brak powiązania —" : item.toString());
            }
        });
        debtCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Debt item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "— brak powiązania —" : item.toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ColumnConstraints col1 = new ColumnConstraints(140);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        grid.add(new Label("Data obietnicy *"), 0, 0); grid.add(datePicker, 1, 0);
        grid.add(new Label("Kwota (PLN) *"),    0, 1); grid.add(amountField, 1, 1);
        grid.add(new Label("Powiązana sprawa"), 0, 2); grid.add(debtCombo, 1, 2);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(480);
        getDialogPane().setMinWidth(480);

        javafx.scene.Node saveNode = getDialogPane().lookupButton(saveBtn);
        saveNode.setDisable(true);
        amountField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        datePicker.valueProperty().addListener((o, ov, nv) -> validateForm(saveNode));

        setResultConverter(btn -> {
            if (btn != saveBtn) return null;
            Debt selectedDebt = debtCombo.getValue();
            return Declaration.builder()
                    .debtorId(debtorId)
                    .debtId(selectedDebt != null ? selectedDebt.getId() : null)
                    .promisedDate(datePicker.getValue())
                    .promisedAmount(new BigDecimal(amountField.getText().trim().replace(",", ".")))
                    .status(DeclarationStatus.OCZEKUJACA)
                    .build();
        });
    }

    private void validateForm(javafx.scene.Node saveNode) {
        boolean valid = datePicker.getValue() != null && isValidAmount(amountField.getText());
        saveNode.setDisable(!valid);
    }

    private boolean isValidAmount(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            return new BigDecimal(text.trim().replace(",", ".")).compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
