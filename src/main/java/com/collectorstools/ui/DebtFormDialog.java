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

    /** Dopuszczalna różnica przy porównywaniu składników salda — jeden grosz. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final TextField  caseNumberField   = new TextField();
    private final TextField  creditorField     = new TextField();
    private final TextField  totalAmountField  = new TextField();
    private final TextField  remainingField    = new TextField();
    private final TextField  principalField    = new TextField();
    private final TextField  interestField     = new TextField();
    private final TextField  costField         = new TextField();
    private final Label      breakdownHint     = new Label();
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

        principalField.setPromptText("kapitał");
        interestField.setPromptText("odsetki");
        costField.setPromptText("koszty");
        breakdownHint.setWrapText(true);

        // Identyfikatory pozwalają testom odnaleźć pola w drzewie sceny
        caseNumberField.setId("caseNumber");
        creditorField.setId("creditor");
        totalAmountField.setId("totalAmount");
        remainingField.setId("remaining");
        principalField.setId("principal");
        interestField.setId("interest");
        costField.setId("cost");
        breakdownHint.setId("breakdownHint");
        descriptionArea.setId("description");

        addRow(grid, 0, "Nr sprawy *",     caseNumberField);
        addRow(grid, 1, "Wierzyciel *",    creditorField);
        addRow(grid, 2, "Kwota całk. *",   totalAmountField);
        addRow(grid, 3, "Pozostało *",     remainingField);
        grid.add(new Separator(), 0, 4, 2, 1);
        addRow(grid, 5, "Kapitał",         principalField);
        addRow(grid, 6, "Odsetki",         interestField);
        addRow(grid, 7, "Koszty",          costField);
        grid.add(breakdownHint, 1, 8);
        addRow(grid, 9, "Data powstania",  debtDatePicker);
        grid.add(new Separator(), 0, 10, 2, 1);
        grid.add(new Label("Opis"), 0, 11);
        grid.add(descriptionArea, 1, 11);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(520);
        getDialogPane().setMinWidth(520);

        if (existing != null) populate(existing);

        javafx.scene.Node saveNode = getDialogPane().lookupButton(saveBtn);
        saveNode.setDisable(true);
        for (TextField field : new TextField[]{caseNumberField, creditorField, totalAmountField,
                remainingField, principalField, interestField, costField}) {
            field.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        }
        validateForm(saveNode);

        setResultConverter(btn -> {
            if (btn != saveBtn) return null;

            // Przy edycji nadpisujemy WYŁĄCZNIE pola obecne w formularzu. Zbudowanie
            // nowego obiektu skasowałoby wszystko, czego formularz nie pokazuje:
            // rozbicie salda, portfel, produkt, dane sądowe oraz stan zamknięcia sprawy —
            // zamknięta sprawa cicho wróciłaby do obsługi.
            Debt result = existing != null
                    ? existing
                    : Debt.builder().debtorId(debtorId).build();

            result.setCaseNumber(caseNumberField.getText().trim());
            result.setCreditor(creditorField.getText().trim());
            result.setTotalAmount(parseBigDecimal(totalAmountField.getText()));
            result.setRemainingAmount(parseBigDecimal(remainingField.getText()));
            result.setPrincipalAmount(parseBigDecimal(principalField.getText()));
            result.setInterestAmount(parseBigDecimal(interestField.getText()));
            result.setCostAmount(parseBigDecimal(costField.getText()));
            result.setDebtDate(debtDatePicker.getValue());
            result.setDescription(descriptionArea.getText().trim());
            return result;
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
        // Rozbicie zerowe oznacza "nieznane" — nie zaśmiecamy nim formularza
        if (isPositive(d.getPrincipalAmount())) principalField.setText(d.getPrincipalAmount().toPlainString());
        if (isPositive(d.getInterestAmount())) interestField.setText(d.getInterestAmount().toPlainString());
        if (isPositive(d.getCostAmount())) costField.setText(d.getCostAmount().toPlainString());
        if (d.getDebtDate() != null) debtDatePicker.setValue(d.getDebtDate());
        if (d.getDescription() != null) descriptionArea.setText(d.getDescription());
    }

    private void validateForm(javafx.scene.Node saveNode) {
        boolean baseValid = !caseNumberField.getText().isBlank()
                && !creditorField.getText().isBlank()
                && isValidAmount(totalAmountField.getText())
                && isValidAmount(remainingField.getText());

        saveNode.setDisable(!baseValid || !isBreakdownValid());
    }

    /**
     * Sprawdza regułę „saldo = kapitał + odsetki + koszty" i opisuje jej stan
     * pod polami rozbicia.
     *
     * <p>Rozbicie jest opcjonalne: windykator wpisujący sprawę ręcznie zna często
     * wyłącznie kwotę łączną. Reguła obowiązuje dopiero wtedy, gdy podano choć jeden
     * składnik — wtedy suma musi zgadzać się z kwotą pozostałą co do grosza.
     * Ta sama reguła jest egzekwowana w {@code DebtService}; tutaj chodzi o to,
     * żeby błąd był widoczny przed kliknięciem „Zapisz".
     */
    private boolean isBreakdownValid() {
        boolean anyFilled = !principalField.getText().isBlank()
                || !interestField.getText().isBlank()
                || !costField.getText().isBlank();

        if (!anyFilled) {
            breakdownHint.setText("Rozbicie salda opcjonalne — pozostaw puste, jeśli nieznane.");
            breakdownHint.setStyle("-fx-text-fill: #7f8c8d;");
            return true;
        }

        BigDecimal principal = parseOrZero(principalField.getText());
        BigDecimal interest = parseOrZero(interestField.getText());
        BigDecimal cost = parseOrZero(costField.getText());
        BigDecimal remaining = parseBigDecimal(remainingField.getText());

        // null oznacza tu wartość wpisaną, ale nieparsowalną — pusta dałaby zero
        if (principal == null || interest == null || cost == null) {
            breakdownHint.setText("Nieprawidłowa kwota w rozbiciu salda.");
            breakdownHint.setStyle("-fx-text-fill: #e74c3c;");
            return false;
        }
        if (principal.signum() < 0 || interest.signum() < 0 || cost.signum() < 0) {
            breakdownHint.setText("Składniki salda nie mogą być ujemne.");
            breakdownHint.setStyle("-fx-text-fill: #e74c3c;");
            return false;
        }

        BigDecimal sum = principal.add(interest).add(cost);
        if (remaining == null) {
            breakdownHint.setText("Suma składników: " + sum.toPlainString() + " zł");
            breakdownHint.setStyle("-fx-text-fill: #7f8c8d;");
            return false;
        }

        if (sum.subtract(remaining).abs().compareTo(TOLERANCE) > 0) {
            breakdownHint.setText("Suma składników: " + sum.toPlainString()
                    + " zł — nie zgadza się z kwotą pozostałą (" + remaining.toPlainString() + " zł).");
            breakdownHint.setStyle("-fx-text-fill: #e74c3c;");
            return false;
        }

        breakdownHint.setText("Suma składników zgadza się z kwotą pozostałą.");
        breakdownHint.setStyle("-fx-text-fill: #27ae60;");
        return true;
    }

    /** Puste pole liczy się jako zero — brak składnika, nie brak danych. */
    private BigDecimal parseOrZero(String text) {
        if (text == null || text.isBlank()) return BigDecimal.ZERO;
        return parseBigDecimal(text);
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean isValidAmount(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            return new BigDecimal(text.trim().replace(",", ".")).compareTo(BigDecimal.ZERO) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Zwraca {@code null} dla pola pustego i dla wartości nieparsowalnej.
     *
     * <p>Metoda jest wołana z listenerów przy każdym naciśnięciu klawisza, więc nie może
     * rzucać wyjątkiem — w trakcie pisania pole przechodzi przez stany pośrednie
     * (pusty tekst, sam minus, sam przecinek), które nie są jeszcze liczbą.
     */
    private BigDecimal parseBigDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.trim().replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
