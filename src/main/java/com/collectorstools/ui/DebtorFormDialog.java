package com.collectorstools.ui;

import com.collectorstools.model.Debtor;
import com.collectorstools.util.PeselValidator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

public class DebtorFormDialog extends Dialog<Debtor> {

    private final TextField firstNameField  = new TextField();
    private final TextField lastNameField   = new TextField();
    private final TextField peselField      = new TextField();
    private final TextField idNumberField   = new TextField();
    private final TextField phoneField      = new TextField();
    private final TextField emailField      = new TextField();
    private final TextField streetField     = new TextField();
    private final TextField cityField       = new TextField();
    private final TextField postalCodeField = new TextField();
    private final TextArea  notesArea       = new TextArea();
    private final CheckBox  urgentCheck     = new CheckBox("⚠ Oznacz jako pilne");
    private final Label     peselInfo       = new Label();

    public DebtorFormDialog(Debtor existing) {
        setTitle(existing == null ? "Nowy dłużnik" : "Edytuj dłużnika");
        setHeaderText(existing == null ? "Wprowadź dane dłużnika" : "Edytuj dane dłużnika");

        ButtonType saveBtn = new ButtonType("Zapisz", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Stała szerokość kolumny etykiet — żeby nie były obcinane
        ColumnConstraints col1 = new ColumnConstraints(140);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        // Wymagane
        addRow(grid, 0, "Imię *", firstNameField);
        addRow(grid, 1, "Nazwisko *", lastNameField);

        // PESEL + dynamiczny podgląd daty urodzenia / płci
        Label peselLbl = new Label("PESEL");
        peselLbl.setMinWidth(130);
        peselField.setMaxWidth(Double.MAX_VALUE);
        peselInfo.getStyleClass().add("pesel-info");
        grid.add(peselLbl, 0, 2);
        grid.add(new VBox(2, peselField, peselInfo), 1, 2);

        addRow(grid, 3, "Nr dowodu", idNumberField);

        grid.add(new Separator(), 0, 4, 2, 1);

        addRow(grid, 5, "Telefon", phoneField);
        addRow(grid, 6, "E-mail", emailField);

        grid.add(new Separator(), 0, 7, 2, 1);

        addRow(grid, 8,  "Ulica", streetField);
        addRow(grid, 9,  "Miasto", cityField);
        addRow(grid, 10, "Kod pocztowy", postalCodeField);

        grid.add(new Separator(), 0, 11, 2, 1);

        notesArea.setPromptText("Notatki ogólne...");
        notesArea.setPrefRowCount(3);
        grid.add(new Label("Notatki"), 0, 12);
        grid.add(notesArea, 1, 12);

        grid.add(urgentCheck, 1, 13);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(520);
        getDialogPane().setMinWidth(520);

        if (existing != null) populate(existing);

        javafx.scene.Node saveNode = getDialogPane().lookupButton(saveBtn);
        saveNode.setDisable(true);
        firstNameField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        lastNameField.textProperty().addListener((o, ov, nv) -> validateForm(saveNode));
        peselField.textProperty().addListener((o, ov, nv) -> {
            updatePeselInfo();
            validateForm(saveNode);
        });
        updatePeselInfo();
        if (existing != null) validateForm(saveNode);

        setResultConverter(btn -> {
            if (btn == saveBtn) return buildDebtor();
            return null;
        });
    }

    private void addRow(GridPane grid, int row, String label, Control field) {
        Label lbl = new Label(label);
        lbl.setMinWidth(130);
        grid.add(lbl, 0, row);
        grid.add(field, 1, row);
        if (field instanceof TextField tf) {
            tf.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private void validateForm(javafx.scene.Node saveNode) {
        String pesel = peselField.getText().trim();
        boolean peselOk = pesel.isEmpty() || PeselValidator.isValid(pesel);
        boolean valid = !firstNameField.getText().isBlank()
                && !lastNameField.getText().isBlank()
                && peselOk;
        saveNode.setDisable(!valid);
    }

    private void updatePeselInfo() {
        String pesel = peselField.getText().trim();
        if (pesel.isEmpty()) {
            peselInfo.setText("");
            return;
        }
        if (!PeselValidator.isValid(pesel)) {
            peselInfo.setText("✖ nieprawidłowy PESEL");
            peselInfo.setStyle("-fx-text-fill: #e74c3c;");
            return;
        }
        LocalDate birth = PeselValidator.birthDate(pesel);
        String gender = PeselValidator.gender(pesel);
        peselInfo.setText("✔ ur. " + birth + ", " + gender);
        peselInfo.setStyle("-fx-text-fill: #27ae60;");
    }

    private void populate(Debtor d) {
        firstNameField.setText(d.getFirstName());
        lastNameField.setText(d.getLastName());
        peselField.setText(d.getPesel() != null ? d.getPesel() : "");
        idNumberField.setText(d.getIdNumber() != null ? d.getIdNumber() : "");
        phoneField.setText(d.getPhone() != null ? d.getPhone() : "");
        emailField.setText(d.getEmail() != null ? d.getEmail() : "");
        streetField.setText(d.getStreet() != null ? d.getStreet() : "");
        cityField.setText(d.getCity() != null ? d.getCity() : "");
        postalCodeField.setText(d.getPostalCode() != null ? d.getPostalCode() : "");
        notesArea.setText(d.getNotes() != null ? d.getNotes() : "");
        urgentCheck.setSelected(d.isUrgent());
    }

    private Debtor buildDebtor() {
        return Debtor.builder()
                .firstName(firstNameField.getText().trim())
                .lastName(lastNameField.getText().trim())
                .pesel(peselField.getText().trim())
                .idNumber(idNumberField.getText().trim())
                .phone(phoneField.getText().trim())
                .email(emailField.getText().trim())
                .street(streetField.getText().trim())
                .city(cityField.getText().trim())
                .postalCode(postalCodeField.getText().trim())
                .notes(notesArea.getText().trim())
                .urgent(urgentCheck.isSelected())
                .build();
    }
}
