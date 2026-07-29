package com.collectorstools.ui;

import com.collectorstools.model.ContactType;
import com.collectorstools.model.Interaction;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.time.LocalDate;

public class InteractionFormDialog extends Dialog<Interaction> {

    private final DatePicker            datePicker = new DatePicker(LocalDate.now());
    private final ComboBox<ContactType> typeCombo  = new ComboBox<>();
    private final TextArea              notesArea  = new TextArea();

    public InteractionFormDialog(Long debtorId, Interaction existing) {
        setTitle(existing == null ? "Nowy kontakt" : "Edytuj kontakt");
        setHeaderText(existing == null ? "Dodaj wpis kontaktu z dłużnikiem" : "Edytuj wpis kontaktu");

        ButtonType saveBtn = new ButtonType("Zapisz", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        typeCombo.getItems().addAll(ContactType.values());
        typeCombo.setValue(ContactType.WIZYTA);
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        notesArea.setPromptText("Notatka z kontaktu...");
        notesArea.setPrefRowCount(4);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        ColumnConstraints col1 = new ColumnConstraints(140);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        grid.add(new Label("Data *"),         0, 0); grid.add(datePicker, 1, 0);
        grid.add(new Label("Typ kontaktu *"), 0, 1); grid.add(typeCombo, 1, 1);
        grid.add(new Separator(),             0, 2, 2, 1);
        grid.add(new Label("Notatka"),        0, 3); grid.add(notesArea, 1, 3);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(480);
        getDialogPane().setMinWidth(480);

        if (existing != null) populate(existing);

        javafx.scene.Node saveNode = getDialogPane().lookupButton(saveBtn);
        datePicker.valueProperty().addListener((o, ov, nv) ->
                saveNode.setDisable(nv == null || typeCombo.getValue() == null));
        typeCombo.valueProperty().addListener((o, ov, nv) ->
                saveNode.setDisable(datePicker.getValue() == null || nv == null));

        setResultConverter(btn -> {
            if (btn != saveBtn) return null;
            return Interaction.builder()
                    .debtorId(debtorId)
                    .interactionDate(datePicker.getValue())
                    .contactType(typeCombo.getValue())
                    .notes(notesArea.getText().trim())
                    .build();
        });
    }

    private void populate(Interaction i) {
        datePicker.setValue(i.getInteractionDate());
        typeCombo.setValue(i.getContactType());
        if (i.getNotes() != null) notesArea.setText(i.getNotes());
    }
}
