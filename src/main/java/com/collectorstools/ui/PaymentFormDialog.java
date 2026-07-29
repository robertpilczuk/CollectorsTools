package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.Payment;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Formularz rejestracji wpłaty.
 *
 * <p>Wpłata zawsze wskazuje sprawę, bo to sprawa ma saldo. Dialog pokazuje pozostałe
 * saldo wybranej sprawy i nie pozwala zatwierdzić kwoty, która je przekracza — ten sam
 * warunek egzekwuje serwis, tu chodzi o to, żeby windykator zobaczył problem od razu,
 * a nie po kliknięciu "Zapisz".
 */
public class PaymentFormDialog extends Dialog<Payment> {

    private final ComboBox<Debt> debtCombo = new ComboBox<>();
    private final TextField amountField = new TextField();
    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final TextField noteField = new TextField();
    private final Label balanceLabel = new Label();
    private final Label errorLabel = new Label();

    private final Declaration declaration;

    /**
     * @param debts       sprawy dłużnika — wpłata musi trafić do jednej z nich
     * @param declaration deklaracja, na poczet której następuje wpłata (może być {@code null})
     * @param alreadyPaid suma dotychczasowych wpłat do tej deklaracji
     */
    public PaymentFormDialog(List<Debt> debts, Declaration declaration, BigDecimal alreadyPaid) {
        this.declaration = declaration;

        setTitle("Rejestracja wpłaty");
        setHeaderText(declaration != null
                ? "Wpłata na poczet deklaracji z " + declaration.getPromisedDate()
                : "Wpłata bez powiązania z deklaracją");

        ButtonType saveButton = new ButtonType("Zapisz wpłatę", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);

        buildForm(debts);
        preselect(debts, declaration, alreadyPaid);

        Node saveNode = getDialogPane().lookupButton(saveButton);
        saveNode.setDisable(true);
        debtCombo.valueProperty().addListener((o, old, value) -> {
            showBalance(value);
            validateForm(saveNode);
        });
        amountField.textProperty().addListener((o, old, value) -> validateForm(saveNode));
        datePicker.valueProperty().addListener((o, old, value) -> validateForm(saveNode));
        validateForm(saveNode);

        setResultConverter(button -> {
            if (button != saveButton) return null;
            return Payment.builder()
                    .debtId(debtCombo.getValue().getId())
                    .declarationId(declaration != null ? declaration.getId() : null)
                    .amount(parseAmount(amountField.getText()))
                    .paymentDate(datePicker.getValue())
                    .note(noteField.getText() != null && !noteField.getText().isBlank()
                            ? noteField.getText().trim() : null)
                    .source(Payment.SOURCE_MANUAL)
                    .build();
        });
    }

    private void buildForm(List<Debt> debts) {
        debtCombo.getItems().addAll(debts);
        debtCombo.setMaxWidth(Double.MAX_VALUE);
        debtCombo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Debt item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : describe(item));
            }
        });
        debtCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Debt item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "— wybierz sprawę —" : describe(item));
            }
        });

        amountField.setPromptText("np. 500,00");
        amountField.setMaxWidth(Double.MAX_VALUE);
        noteField.setPromptText("np. wpłata gotówką przy wizycie");
        noteField.setMaxWidth(Double.MAX_VALUE);

        balanceLabel.setStyle("-fx-text-fill: #2980b9;");
        errorLabel.setStyle("-fx-text-fill: #e74c3c;");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ColumnConstraints labels = new ColumnConstraints(140);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);

        grid.add(label("Sprawa *"), 0, 0);        grid.add(debtCombo, 1, 0);
        grid.add(new Label(""), 0, 1);            grid.add(balanceLabel, 1, 1);
        grid.add(label("Kwota (PLN) *"), 0, 2);   grid.add(amountField, 1, 2);
        grid.add(label("Data wpłaty *"), 0, 3);   grid.add(datePicker, 1, 3);
        grid.add(label("Notatka"), 0, 4);         grid.add(noteField, 1, 4);
        grid.add(errorLabel, 1, 5);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(520);
        getDialogPane().setMinWidth(520);
    }

    private Label label(String text) {
        Label label = new Label(text);
        label.setMinWidth(130);
        return label;
    }

    /**
     * Podpowiada sprawę i kwotę. Przy wpłacie do deklaracji proponuje kwotę
     * brakującą do jej wypełnienia, ograniczoną saldem sprawy — windykator
     * najczęściej rejestruje dokładnie tyle.
     */
    private void preselect(List<Debt> debts, Declaration declaration, BigDecimal alreadyPaid) {
        Debt selected = null;
        if (declaration != null && declaration.getDebtId() != null) {
            selected = debts.stream()
                    .filter(d -> declaration.getDebtId().equals(d.getId()))
                    .findFirst().orElse(null);
        }
        if (selected == null && debts.size() == 1) {
            selected = debts.get(0);
        }
        debtCombo.setValue(selected);
        showBalance(selected);

        if (declaration != null && selected != null) {
            BigDecimal missing = declaration.getPromisedAmount().subtract(orZero(alreadyPaid));
            BigDecimal suggestion = missing.min(orZero(selected.getRemainingAmount()));
            if (suggestion.compareTo(BigDecimal.ZERO) > 0) {
                amountField.setText(money(suggestion));
            }
        }
    }

    private void showBalance(Debt debt) {
        balanceLabel.setText(debt == null
                ? "" : "Pozostałe saldo sprawy: " + money(debt.getRemainingAmount()) + " zł");
    }

    private void validateForm(Node saveNode) {
        errorLabel.setText("");

        Debt debt = debtCombo.getValue();
        if (debt == null || datePicker.getValue() == null) {
            saveNode.setDisable(true);
            return;
        }

        BigDecimal amount = parseAmount(amountField.getText());
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            saveNode.setDisable(true);
            return;
        }

        BigDecimal remaining = orZero(debt.getRemainingAmount());
        if (amount.compareTo(remaining) > 0) {
            errorLabel.setText("Kwota przekracza pozostałe saldo sprawy ("
                    + money(remaining) + " zł).");
            saveNode.setDisable(true);
            return;
        }

        if (declaration != null && datePicker.getValue().isAfter(declaration.getPromisedDate())) {
            errorLabel.setText("Uwaga: wpłata po terminie deklaracji — zostanie tak odnotowana.");
            errorLabel.setStyle("-fx-text-fill: #e67e22;");
        } else {
            errorLabel.setStyle("-fx-text-fill: #e74c3c;");
        }

        saveNode.setDisable(false);
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.trim().replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String money(BigDecimal value) {
        return orZero(value).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String describe(Debt debt) {
        return debt.getCaseNumber() + " — " + debt.getCreditor()
                + " (pozostało " + money(debt.getRemainingAmount()) + " zł)";
    }
}
