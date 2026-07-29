package com.collectorstools.ui;

import com.collectorstools.model.*;
import com.collectorstools.service.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DebtorDetailWindow extends Stage {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Debtor debtor;
    private final DeclarationService declarationService = new DeclarationService();
    private final DebtService debtService = new DebtService();
    private final InteractionService interactionService = new InteractionService();
    private final DebtorAuditService auditService = new DebtorAuditService();

    // Zakładki
    private final TableView<Declaration> declarationTable = new TableView<>();
    private final TableView<Debt> debtTable = new TableView<>();
    private final TableView<Interaction> interactionTable = new TableView<>();

    public DebtorDetailWindow(Debtor debtor) {
        this.debtor = debtor;

        setTitle("Dłużnik: " + debtor.getFullName());
        setWidth(860);
        setHeight(640);
        setMinWidth(700);
        setMinHeight(500);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("detail-root");

        root.setTop(buildHeader());
        root.setCenter(buildTabs());

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        setScene(scene);
    }

    private VBox buildHeader() {
        Text name = new Text(debtor.getFullName());
        name.getStyleClass().add("detail-name");

        String info = buildInfoLine();
        Label infoLabel = new Label(info);
        infoLabel.getStyleClass().add("detail-info");

        // Statystyki
        long brokenCount = declarationService.countBrokenByDebtorId(debtor.getId());
        int contactCount = interactionService.countByDebtorId(debtor.getId());
        BigDecimal totalRemaining = debtService.getTotalRemainingForDebtor(debtor.getId());

        HBox stats = new HBox(24,
                statBox("Złamane deklaracje", brokenCount > 0 ? "⚠ " + brokenCount : "0", brokenCount > 0 ? "stat-danger" : "stat-ok"),
                statBox("Liczba kontaktów", String.valueOf(contactCount), "stat-neutral"),
                statBox("Pozostały dług", totalRemaining.toPlainString() + " PLN", "stat-neutral")
        );
        stats.getStyleClass().add("stats-bar");

        Button exportPdfBtn = new Button("Eksportuj PDF");
        exportPdfBtn.getStyleClass().add("btn-detail");
        exportPdfBtn.setOnAction(e -> exportToPdf());

        Region spacer = new Region();
        HBox titleRow = new HBox(8, name, spacer, exportPdfBtn);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, titleRow, infoLabel, stats);
        header.setPadding(new Insets(16));
        header.getStyleClass().add("detail-header");
        return header;
    }

    private String buildInfoLine() {
        StringBuilder sb = new StringBuilder();
        if (debtor.getPesel() != null && !debtor.getPesel().isBlank())
            sb.append("PESEL: ").append(debtor.getPesel()).append("  ");
        if (debtor.getPhone() != null && !debtor.getPhone().isBlank())
            sb.append("☎ ").append(debtor.getPhone()).append("  ");
        if (debtor.getCity() != null && !debtor.getCity().isBlank())
            sb.append("📍 ").append(debtor.getCity());
        return sb.toString();
    }

    private VBox statBox(String label, String value, String styleClass) {
        Label val = new Label(value);
        val.getStyleClass().addAll("stat-value", styleClass);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("stat-label");
        VBox box = new VBox(2, val, lbl);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("stat-box");
        return box;
    }

    private void exportToPdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Eksportuj kartę dłużnika do PDF");
        chooser.setInitialFileName("karta_" + debtor.getLastName() + "_" + debtor.getFirstName() + ".pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pliki PDF", "*.pdf"));
        File file = chooser.showSaveDialog(this);
        if (file == null) return;

        try {
            new DebtorPdfExporter().export(debtor, file);
            showAlert(Alert.AlertType.INFORMATION, "Eksport zakończony",
                    "Zapisano kartę dłużnika do:\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Błąd eksportu PDF", ex.getMessage());
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private TabPane buildTabs() {
        Tab declarationsTab  = new Tab("Deklaracje",   buildDeclarationsPane());
        Tab debtsTab         = new Tab("Sprawy/Długi", buildDebtsPane());
        Tab interactionsTab  = new Tab("Historia kontaktów", buildInteractionsPane());
        Tab auditTab         = new Tab("Historia zmian", buildAuditPane());

        declarationsTab.setClosable(false);
        debtsTab.setClosable(false);
        interactionsTab.setClosable(false);
        auditTab.setClosable(false);

        TabPane tabs = new TabPane(declarationsTab, debtsTab, interactionsTab, auditTab);
        tabs.getStyleClass().add("detail-tabs");
        return tabs;
    }

    // ===== HISTORIA ZMIAN =====

    private VBox buildAuditPane() {
        TableView<DebtorAudit> table = new TableView<>();

        TableColumn<DebtorAudit, String> dateCol = new TableColumn<>("Data");
        dateCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().getChangedAt() != null ? d.getValue().getChangedAt().format(DT_FMT) : "—"));
        dateCol.setPrefWidth(140);

        TableColumn<DebtorAudit, String> fieldCol = new TableColumn<>("Pole");
        fieldCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getFieldName()));
        fieldCol.setPrefWidth(130);

        TableColumn<DebtorAudit, String> oldCol = new TableColumn<>("Poprzednio");
        oldCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getOldValue()));
        oldCol.setPrefWidth(200);

        TableColumn<DebtorAudit, String> newCol = new TableColumn<>("Teraz");
        newCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getNewValue()));
        newCol.setPrefWidth(200);

        table.getColumns().addAll(List.of(dateCol, fieldCol, oldCol, newCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Brak zarejestrowanych zmian."));
        table.setItems(FXCollections.observableArrayList(auditService.findByDebtorId(debtor.getId())));

        VBox box = new VBox(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    // ===== DEKLARACJE =====

    @SuppressWarnings("unchecked")
    private VBox buildDeclarationsPane() {
        TableColumn<Declaration, String> dateCol = new TableColumn<>("Data obietnicy");
        dateCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getPromisedDate().format(FMT)));
        dateCol.setPrefWidth(120);

        TableColumn<Declaration, String> amountCol = new TableColumn<>("Kwota (PLN)");
        amountCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getPromisedAmount().toPlainString()));
        amountCol.setPrefWidth(110);

        TableColumn<Declaration, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getStatus().getDisplayName()));
        statusCol.setPrefWidth(110);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                if (item.equals("Złamana"))      setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                else if (item.equals("Dotrzymana")) setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
                else                                setStyle("-fx-text-fill: #e67e22;");
            }
        });

        TableColumn<Declaration, String> reasonCol = new TableColumn<>("Powód złamania");
        reasonCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        d.getValue().getBrokenReason() != null ? d.getValue().getBrokenReason() : ""));
        reasonCol.setPrefWidth(200);

        TableColumn<Declaration, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(220);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button fulfillBtn = new Button("✓ Wpłata");
            final Button brokenBtn  = new Button("✕ Złamana");
            final Button deleteBtn  = new Button("Usuń");
            {
                fulfillBtn.getStyleClass().add("btn-ok");
                brokenBtn.getStyleClass().add("btn-danger");
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");

                fulfillBtn.setOnAction(e -> markFulfilled(getTableView().getItems().get(getIndex())));
                brokenBtn.setOnAction(e ->  markBroken(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e ->  deleteDeclaration(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                Declaration d = getTableView().getItems().get(getIndex());
                HBox box = new HBox(4);
                if (d.getStatus() == DeclarationStatus.OCZEKUJACA) {
                    box.getChildren().addAll(fulfillBtn, brokenBtn);
                }
                box.getChildren().add(deleteBtn);
                setGraphic(box);
            }
        });

        declarationTable.getColumns().addAll(dateCol, amountCol, statusCol, reasonCol, actionsCol);
        declarationTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        declarationTable.setPlaceholder(new Label("Brak deklaracji."));
        loadDeclarations();

        Button addBtn = new Button("+ Dodaj deklarację");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openDeclarationForm());

        HBox toolbar = new HBox(addBtn);
        toolbar.setPadding(new Insets(8, 0, 8, 0));

        VBox pane = new VBox(toolbar, declarationTable);
        VBox.setVgrow(declarationTable, Priority.ALWAYS);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ===== SPRAWY =====

    @SuppressWarnings("unchecked")
    private VBox buildDebtsPane() {
        TableColumn<Debt, String> caseCol = new TableColumn<>("Nr sprawy");
        caseCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getCaseNumber()));
        caseCol.setPrefWidth(130);

        TableColumn<Debt, String> creditorCol = new TableColumn<>("Wierzyciel");
        creditorCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getCreditor()));
        creditorCol.setPrefWidth(160);

        TableColumn<Debt, String> totalCol = new TableColumn<>("Kwota całk.");
        totalCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getTotalAmount().toPlainString() + " PLN"));
        totalCol.setPrefWidth(120);

        TableColumn<Debt, String> remainingCol = new TableColumn<>("Pozostało");
        remainingCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getRemainingAmount().toPlainString() + " PLN"));
        remainingCol.setPrefWidth(120);

        TableColumn<Debt, String> descCol = new TableColumn<>("Opis");
        descCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        d.getValue().getDescription() != null ? d.getValue().getDescription() : ""));

        TableColumn<Debt, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(140);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button editBtn   = new Button("Edytuj");
            final Button deleteBtn = new Button("Usuń");
            {
                editBtn.getStyleClass().add("btn-edit");
                deleteBtn.getStyleClass().add("btn-danger");
                editBtn.setOnAction(e -> openDebtForm(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e -> deleteDebt(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                setGraphic(new HBox(6, editBtn, deleteBtn));
            }
        });

        debtTable.getColumns().addAll(caseCol, creditorCol, totalCol, remainingCol, descCol, actionsCol);
        debtTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        debtTable.setPlaceholder(new Label("Brak spraw."));
        loadDebts();

        Button addBtn = new Button("+ Dodaj sprawę");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openDebtForm(null));

        HBox toolbar = new HBox(addBtn);
        toolbar.setPadding(new Insets(8, 0, 8, 0));

        VBox pane = new VBox(toolbar, debtTable);
        VBox.setVgrow(debtTable, Priority.ALWAYS);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ===== INTERAKCJE =====

    @SuppressWarnings("unchecked")
    private VBox buildInteractionsPane() {
        TableColumn<Interaction, String> dateCol = new TableColumn<>("Data");
        dateCol.setCellValueFactory(i ->
                new javafx.beans.property.SimpleStringProperty(i.getValue().getInteractionDate().format(FMT)));
        dateCol.setPrefWidth(100);

        TableColumn<Interaction, String> typeCol = new TableColumn<>("Typ kontaktu");
        typeCol.setCellValueFactory(i ->
                new javafx.beans.property.SimpleStringProperty(i.getValue().getContactType().getDisplayName()));
        typeCol.setPrefWidth(140);

        TableColumn<Interaction, String> notesCol = new TableColumn<>("Notatka");
        notesCol.setCellValueFactory(i ->
                new javafx.beans.property.SimpleStringProperty(i.getValue().getNotes() != null ? i.getValue().getNotes() : ""));

        TableColumn<Interaction, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(130);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button editBtn   = new Button("Edytuj");
            final Button deleteBtn = new Button("Usuń");
            {
                editBtn.getStyleClass().add("btn-edit");
                deleteBtn.getStyleClass().add("btn-danger");
                editBtn.setOnAction(e -> openInteractionForm(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e -> deleteInteraction(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                setGraphic(new HBox(6, editBtn, deleteBtn));
            }
        });

        interactionTable.getColumns().addAll(dateCol, typeCol, notesCol, actionsCol);
        interactionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        interactionTable.setPlaceholder(new Label("Brak historii kontaktów."));
        loadInteractions();

        Button addBtn = new Button("+ Dodaj kontakt");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openInteractionForm(null));

        HBox toolbar = new HBox(addBtn);
        toolbar.setPadding(new Insets(8, 0, 8, 0));

        VBox pane = new VBox(toolbar, interactionTable);
        VBox.setVgrow(interactionTable, Priority.ALWAYS);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ===== LOAD =====

    private void loadDeclarations() {
        List<Declaration> list = declarationService.findByDebtorId(debtor.getId());
        declarationTable.setItems(FXCollections.observableArrayList(list));
    }

    private void loadDebts() {
        List<Debt> list = debtService.findByDebtorId(debtor.getId());
        debtTable.setItems(FXCollections.observableArrayList(list));
    }

    private void loadInteractions() {
        List<Interaction> list = interactionService.findByDebtorId(debtor.getId());
        interactionTable.setItems(FXCollections.observableArrayList(list));
    }

    // ===== AKCJE — DEKLARACJE =====

    private void openDeclarationForm() {
        DeclarationFormDialog dialog = new DeclarationFormDialog(debtor.getId(),
                debtService.findByDebtorId(debtor.getId()));
        dialog.showAndWait().ifPresent(d -> {
            declarationService.save(d);
            loadDeclarations();
        });
    }

    private void markFulfilled(Declaration d) {
        declarationService.markAsFulfilled(d.getId(), null);
        loadDeclarations();
    }

    private void markBroken(Declaration d) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Złamana deklaracja");
        dialog.setHeaderText("Podaj powód niewywiązania się z deklaracji:");
        dialog.setContentText("Powód:");
        dialog.showAndWait().ifPresent(reason -> {
            declarationService.markAsBroken(d.getId(), reason);
            loadDeclarations();
        });
    }

    private void deleteDeclaration(Declaration d) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Usunąć deklarację z dnia " + d.getPromisedDate().format(FMT) + "?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) { declarationService.delete(d.getId()); loadDeclarations(); }
        });
    }

    // ===== AKCJE — SPRAWY =====

    private void openDebtForm(Debt existing) {
        DebtFormDialog dialog = new DebtFormDialog(debtor.getId(), existing);
        dialog.showAndWait().ifPresent(d -> {
            if (existing == null) debtService.save(d);
            else { d.setId(existing.getId()); debtService.update(d); }
            loadDebts();
        });
    }

    private void deleteDebt(Debt d) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Usunąć sprawę " + d.getCaseNumber() + "?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) { debtService.delete(d.getId()); loadDebts(); }
        });
    }

    // ===== AKCJE — INTERAKCJE =====

    private void openInteractionForm(Interaction existing) {
        InteractionFormDialog dialog = new InteractionFormDialog(debtor.getId(), existing);
        dialog.showAndWait().ifPresent(i -> {
            if (existing == null) interactionService.save(i);
            else { i.setId(existing.getId()); interactionService.update(i); }
            loadInteractions();
        });
    }

    private void deleteInteraction(Interaction i) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Usunąć wpis kontaktu z dnia " + i.getInteractionDate().format(FMT) + "?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) { interactionService.delete(i.getId()); loadInteractions(); }
        });
    }
}
