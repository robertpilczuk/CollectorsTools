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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DebtorDetailWindow extends Stage {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Debtor debtor;
    private final DeclarationService declarationService = new DeclarationService();
    private final DebtService debtService = new DebtService();
    private final InteractionService interactionService = new InteractionService();
    private final DebtorAuditService auditService = new DebtorAuditService();
    private final AuditService auditLog = new AuditService();
    private final PaymentService paymentService = new PaymentService();

    // Zakładki
    private final TableView<Declaration> declarationTable = new TableView<>();
    private final TableView<Debt> debtTable = new TableView<>();
    private final TableView<Interaction> interactionTable = new TableView<>();
    private final TableView<Payment> paymentTable = new TableView<>();

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

        BigDecimal totalPaid = paymentService.sumByDebtorId(debtor.getId());

        HBox stats = new HBox(24,
                statBox("Złamane deklaracje", brokenCount > 0 ? "⚠ " + brokenCount : "0", brokenCount > 0 ? "stat-danger" : "stat-ok"),
                statBox("Liczba kontaktów", String.valueOf(contactCount), "stat-neutral"),
                statBox("Pozostały dług", money(totalRemaining) + " PLN", "stat-neutral"),
                statBox("Odzyskano", money(totalPaid) + " PLN",
                        totalPaid.compareTo(BigDecimal.ZERO) > 0 ? "stat-ok" : "stat-neutral")
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
        Tab paymentsTab      = new Tab("Wpłaty",       buildPaymentsPane());
        Tab debtsTab         = new Tab("Sprawy/Długi", buildDebtsPane());
        Tab interactionsTab  = new Tab("Historia kontaktów", buildInteractionsPane());
        Tab auditTab         = new Tab("Historia zmian", buildAuditPane());

        declarationsTab.setClosable(false);
        paymentsTab.setClosable(false);
        debtsTab.setClosable(false);
        interactionsTab.setClosable(false);
        auditTab.setClosable(false);

        TabPane tabs = new TabPane(declarationsTab, paymentsTab, debtsTab, interactionsTab, auditTab);
        tabs.getStyleClass().add("detail-tabs");
        return tabs;
    }

    // ===== WPŁATY =====

    @SuppressWarnings("unchecked")
    private VBox buildPaymentsPane() {
        TableColumn<Payment, String> dateCol = new TableColumn<>("Data wpłaty");
        dateCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                p.getValue().getPaymentDate().format(FMT)));
        dateCol.setPrefWidth(110);

        TableColumn<Payment, String> amountCol = new TableColumn<>("Kwota");
        amountCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                money(p.getValue().getAmount()) + " zł"));
        amountCol.setPrefWidth(110);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Payment, String> caseCol = new TableColumn<>("Sprawa");
        caseCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                debtService.findById(p.getValue().getDebtId())
                        .map(Debt::getCaseNumber).orElse("—")));
        caseCol.setPrefWidth(130);

        // Rozbicie pokazuje, na co poszła wpłata — kolejność zaliczania z art. 451 KC
        TableColumn<Payment, String> allocationCol = new TableColumn<>("Zaliczono (K / O / Kap.)");
        allocationCol.setCellValueFactory(p -> {
            Payment payment = p.getValue();
            if (payment.getAllocatedCost() == null) {
                return new javafx.beans.property.SimpleStringProperty("—");
            }
            return new javafx.beans.property.SimpleStringProperty(
                    money(payment.getAllocatedCost()) + " / "
                            + money(payment.getAllocatedInterest()) + " / "
                            + money(payment.getAllocatedPrincipal()));
        });
        allocationCol.setPrefWidth(190);

        TableColumn<Payment, String> noteCol = new TableColumn<>("Notatka");
        noteCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(
                p.getValue().getNote() != null ? p.getValue().getNote() : ""));

        TableColumn<Payment, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(110);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button reverseBtn = new Button("Wycofaj");
            {
                reverseBtn.getStyleClass().addAll("btn-danger", "btn-sm");
                reverseBtn.setOnAction(e -> reversePayment(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : reverseBtn);
            }
        });

        paymentTable.getColumns().addAll(dateCol, amountCol, caseCol, allocationCol, noteCol, actionsCol);
        paymentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        paymentTable.setPlaceholder(new Label("Brak zarejestrowanych wpłat."));
        loadPayments();

        Button addBtn = new Button("+ Zarejestruj wpłatę");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openPaymentForm(null));

        HBox toolbar = new HBox(addBtn);
        toolbar.setPadding(new Insets(8, 0, 8, 0));

        VBox pane = new VBox(toolbar, paymentTable);
        VBox.setVgrow(paymentTable, Priority.ALWAYS);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ===== HISTORIA ZMIAN =====

    /**
     * Jeden wiersz historii — niezależnie od tego, czy pochodzi z dziennika audytowego,
     * czy ze starszej tabeli zmian danych dłużnika.
     */
    private record HistoryRow(LocalDateTime at, String user, String entity, String operation,
                              String field, String change, String reason, boolean significant) {
    }

    /**
     * Historia łączy dwa źródła: dziennik audytowy (operacje na sprawach, deklaracjach
     * i wpłatach) oraz starszą tabelę zmian pól dłużnika. Dla użytkownika to jedna oś
     * czasu — rozdzielanie jej według tego, gdzie akurat trafił zapis, byłoby sztuczne.
     */
    private VBox buildAuditPane() {
        List<HistoryRow> rows = new ArrayList<>();

        for (AuditEntry entry : auditLog.findByDebtorId(debtor.getId())) {
            rows.add(new HistoryRow(
                    entry.getOccurredAt(),
                    entry.getUserId() != null ? entry.getUserId() : "—",
                    entry.getEntityType().getDisplayName(),
                    entry.getOperation().getDisplayName(),
                    entry.getFieldName() != null ? entry.getFieldName() : "",
                    entry.describeChange(),
                    entry.getReason() != null ? entry.getReason() : "",
                    entry.getOperation().isSignificant()));
        }

        for (DebtorAudit change : auditService.findByDebtorId(debtor.getId())) {
            rows.add(new HistoryRow(
                    change.getChangedAt(),
                    "—",
                    AuditEntity.DEBTOR.getDisplayName(),
                    AuditOperation.UPDATE.getDisplayName(),
                    change.getFieldName(),
                    (change.getOldValue() == null || change.getOldValue().isBlank() ? "(puste)" : change.getOldValue())
                            + " → "
                            + (change.getNewValue() == null || change.getNewValue().isBlank() ? "(puste)" : change.getNewValue()),
                    "",
                    false));
        }

        rows.sort(Comparator.comparing(HistoryRow::at,
                Comparator.nullsLast(Comparator.reverseOrder())));

        TableView<HistoryRow> table = new TableView<>();

        table.getColumns().addAll(List.of(
                historyColumn("Data", 130, row -> row.at() != null ? row.at().format(DT_FMT) : "—"),
                historyColumn("Kto", 90, HistoryRow::user),
                historyColumn("Obiekt", 90, HistoryRow::entity),
                historyColumn("Operacja", 150, HistoryRow::operation),
                historyColumn("Pole", 120, HistoryRow::field),
                historyColumn("Zmiana", 200, HistoryRow::change),
                historyColumn("Uzasadnienie", 200, HistoryRow::reason)));

        // Operacje finansowe i decyzje o stanie sprawy muszą rzucać się w oczy
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(HistoryRow item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.significant()
                        ? "-fx-font-weight: bold;" : "");
            }
        });

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Brak zarejestrowanych zmian."));
        table.setItems(FXCollections.observableArrayList(rows));

        VBox box = new VBox(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private TableColumn<HistoryRow, String> historyColumn(String title, double width,
                                                          java.util.function.Function<HistoryRow, String> getter) {
        TableColumn<HistoryRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(getter.apply(row.getValue())));
        column.setPrefWidth(width);
        return column;
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
        statusCol.setCellValueFactory(d -> {
            Declaration decl = d.getValue();
            String label = decl.getStatus().getDisplayName();
            // Wplata po terminie to nadal dotrzymanie — ale windykator musi widziec roznice
            if (decl.getStatus() == DeclarationStatus.FULFILLED && decl.isPaidLate()) {
                label += " (po terminie)";
            }
            return new javafx.beans.property.SimpleStringProperty(label);
        });
        statusCol.setPrefWidth(150);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                Declaration d = (getTableRow() == null) ? null : (Declaration) getTableRow().getItem();
                if (empty || item == null || d == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(switch (d.getStatus()) {
                    case FULFILLED           -> "-fx-text-fill: #27ae60; -fx-font-weight: bold;";
                    case PARTIALLY_FULFILLED -> "-fx-text-fill: #2980b9; -fx-font-weight: bold;";
                    case BROKEN              -> "-fx-text-fill: #e74c3c; -fx-font-weight: bold;";
                    case CANCELLED           -> "-fx-text-fill: #95a5a6; -fx-font-style: italic;";
                    case PENDING             -> "-fx-text-fill: #e67e22;";
                });
            }
        });

        TableColumn<Declaration, String> reasonCol = new TableColumn<>("Powód");
        reasonCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        d.getValue().getReason() != null ? d.getValue().getReason() : ""));
        reasonCol.setPrefWidth(200);

        TableColumn<Declaration, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(280);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button fulfillBtn = new Button("✓ Wpłata");
            final Button brokenBtn  = new Button("✕ Złamana");
            final Button cancelBtn  = new Button("Anuluj");
            final Button deleteBtn  = new Button("Usuń");
            {
                fulfillBtn.getStyleClass().add("btn-ok");
                brokenBtn.getStyleClass().add("btn-danger");
                cancelBtn.getStyleClass().addAll("btn-detail", "btn-sm");
                deleteBtn.getStyleClass().addAll("btn-danger", "btn-sm");

                fulfillBtn.setOnAction(e -> markFulfilled(getTableView().getItems().get(getIndex())));
                brokenBtn.setOnAction(e ->  markBroken(getTableView().getItems().get(getIndex())));
                cancelBtn.setOnAction(e ->  markCancelled(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e ->  deleteDeclaration(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                Declaration d = getTableView().getItems().get(getIndex());
                HBox box = new HBox(4);
                switch (d.getStatus()) {
                    // Oczekujaca — pelny zestaw decyzji windykatora
                    case PENDING, PARTIALLY_FULFILLED ->
                            box.getChildren().addAll(fulfillBtn, brokenBtn, cancelBtn);
                    // Zlamana — wplata po terminie nadal mozliwa, powod zlamania zostaje
                    case BROKEN ->
                            box.getChildren().addAll(fulfillBtn, cancelBtn);
                    // Rozliczona lub wycofana — bez zmian stanu
                    case FULFILLED, CANCELLED -> { }
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
                new javafx.beans.property.SimpleStringProperty(money(d.getValue().getTotalAmount()) + " zł"));
        totalCol.setPrefWidth(110);
        totalCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Debt, String> paidCol = new TableColumn<>("Wpłacono");
        paidCol.setCellValueFactory(d -> {
            BigDecimal paid = paymentService.findByDebtId(d.getValue().getId()).stream()
                    .map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new javafx.beans.property.SimpleStringProperty(money(paid) + " zł");
        });
        paidCol.setPrefWidth(110);
        paidCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Debt, String> remainingCol = new TableColumn<>("Pozostało");
        remainingCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(money(d.getValue().getRemainingAmount()) + " zł"));
        remainingCol.setPrefWidth(110);
        remainingCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Debt, String> statusCol = new TableColumn<>("Stan");
        statusCol.setCellValueFactory(d -> {
            Debt debt = d.getValue();
            String label = debt.getStatus() != null ? debt.getStatus().getDisplayName() : "";
            return new javafx.beans.property.SimpleStringProperty(label);
        });
        statusCol.setPrefWidth(160);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                Debt debt = (getTableRow() == null) ? null : (Debt) getTableRow().getItem();
                if (empty || item == null || debt == null || debt.getStatus() == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                // Przyczyna zamknięcia w podpowiedzi — nie zabiera miejsca w tabeli
                setTooltip(debt.getCloseReason() != null ? new Tooltip(debt.getCloseReason()) : null);
                setStyle(switch (debt.getStatus()) {
                    case ACTIVE            -> "-fx-text-fill: #e67e22;";
                    case CLOSED_PAID       -> "-fx-text-fill: #27ae60; -fx-font-weight: bold;";
                    case CLOSED_WRITTEN_OFF,
                         CLOSED_TIME_BARRED -> "-fx-text-fill: #95a5a6; -fx-font-style: italic;";
                    case CLOSED_LEGAL      -> "-fx-text-fill: #8e44ad; -fx-font-weight: bold;";
                });
            }
        });

        TableColumn<Debt, String> descCol = new TableColumn<>("Opis");
        descCol.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(
                        d.getValue().getDescription() != null ? d.getValue().getDescription() : ""));

        TableColumn<Debt, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(240);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button editBtn   = new Button("Edytuj");
            final Button closeBtn  = new Button("Zamknij");
            final Button reopenBtn = new Button("Otwórz");
            final Button deleteBtn = new Button("Usuń");
            {
                editBtn.getStyleClass().add("btn-edit");
                closeBtn.getStyleClass().addAll("btn-detail", "btn-sm");
                reopenBtn.getStyleClass().addAll("btn-detail", "btn-sm");
                deleteBtn.getStyleClass().add("btn-danger");

                editBtn.setOnAction(e -> openDebtForm(getTableView().getItems().get(getIndex())));
                closeBtn.setOnAction(e -> closeCase(getTableView().getItems().get(getIndex())));
                reopenBtn.setOnAction(e -> reopenCase(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e -> deleteDebt(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                Debt debt = getTableView().getItems().get(getIndex());
                HBox box = new HBox(6, editBtn);
                box.getChildren().add(debt.isClosed() ? reopenBtn : closeBtn);
                box.getChildren().add(deleteBtn);
                setGraphic(box);
            }
        });

        debtTable.getColumns().addAll(caseCol, creditorCol, totalCol, paidCol, remainingCol,
                statusCol, descCol, actionsCol);
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

    private void loadPayments() {
        List<Payment> list = paymentService.findByDebtorId(debtor.getId());
        paymentTable.setItems(FXCollections.observableArrayList(list));
    }

    /** Kwota w zapisie polskim: dwa miejsca po przecinku, przecinek dziesiętny. */
    private static String money(BigDecimal value) {
        BigDecimal amount = value != null ? value : BigDecimal.ZERO;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
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

    /**
     * Oznaczenie deklaracji jako dotrzymanej przechodzi przez rejestrację wpłaty —
     * status wynika z faktycznie zaksięgowanej kwoty, a nie z samej deklaracji
     * windykatora. Dzięki temu saldo sprawy i stan deklaracji nie mogą się rozjechać.
     */
    private void markFulfilled(Declaration d) {
        openPaymentForm(d);
    }

    private void openPaymentForm(Declaration declaration) {
        List<Debt> debts = debtService.findByDebtorId(debtor.getId());
        if (debts.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Brak sprawy",
                    "Wpłatę można zarejestrować tylko na poczet sprawy.\n"
                            + "Dodaj najpierw sprawę w zakładce \"Sprawy/Długi\".");
            return;
        }

        BigDecimal alreadyPaid = declaration != null
                ? paymentService.sumByDeclarationId(declaration.getId()) : BigDecimal.ZERO;

        new PaymentFormDialog(debts, declaration, alreadyPaid).showAndWait().ifPresent(payment -> {
            try {
                paymentService.registerPayment(payment);
                refreshAfterPayment();
            } catch (IllegalArgumentException ex) {
                showAlert(Alert.AlertType.WARNING, "Nie zarejestrowano wpłaty", ex.getMessage());
            }
        });
    }

    private void reversePayment(Payment payment) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Wycofać wpłatę " + money(payment.getAmount()) + " zł z dnia "
                        + payment.getPaymentDate().format(FMT) + "?\n\n"
                        + "Saldo sprawy wróci do stanu sprzed wpłaty, a powiązana deklaracja\n"
                        + "zostanie przeliczona na nowo.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Wycofanie wpłaty");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.OK) {
                try {
                    paymentService.reversePayment(payment.getId());
                    refreshAfterPayment();
                } catch (IllegalArgumentException ex) {
                    showAlert(Alert.AlertType.WARNING, "Nie wycofano wpłaty", ex.getMessage());
                }
            }
        });
    }

    /**
     * Wpłata dotyka trzech widoków naraz: listy wpłat, salda sprawy i stanu deklaracji.
     */
    private void refreshAfterPayment() {
        loadPayments();
        loadDebts();
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

    /**
     * Anulowanie wycofuje deklarację ze wskaźnika skuteczności, dlatego
     * uzasadnienie jest obowiązkowe — puste pole przerywa operację.
     */
    private void markCancelled(Declaration d) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Anulowanie deklaracji");
        dialog.setHeaderText("Deklaracja zostanie wycofana z ewidencji i nie wliczy się\n"
                + "do skuteczności. Podaj uzasadnienie:");
        dialog.setContentText("Uzasadnienie:");
        dialog.showAndWait().ifPresent(reason -> {
            if (reason.isBlank()) {
                showAlert(Alert.AlertType.WARNING, "Anulowanie deklaracji",
                        "Bez uzasadnienia nie można anulować deklaracji. Operacja przerwana.");
                return;
            }
            try {
                declarationService.markAsCancelled(d.getId(), reason);
                loadDeclarations();
            } catch (IllegalArgumentException ex) {
                showAlert(Alert.AlertType.WARNING, "Anulowanie niemożliwe", ex.getMessage());
            }
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

    private void closeCase(Debt debt) {
        new CaseClosureDialog(debt).showAndWait().ifPresent(result -> {
            try {
                debtService.closeCase(debt.getId(), result.status(), result.reason());
                loadDebts();
            } catch (IllegalArgumentException ex) {
                showAlert(Alert.AlertType.WARNING, "Nie zamknięto sprawy", ex.getMessage());
            }
        });
    }

    private void reopenCase(Debt debt) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Przywrócić sprawę " + debt.getCaseNumber() + " do obsługi?\n\n"
                        + "Data i przyczyna zamknięcia zostaną usunięte.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Ponowne otwarcie sprawy");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.OK) {
                try {
                    debtService.reopenCase(debt.getId());
                    loadDebts();
                } catch (IllegalArgumentException ex) {
                    showAlert(Alert.AlertType.WARNING, "Nie otwarto sprawy", ex.getMessage());
                }
            }
        });
    }

    private void deleteDebt(Debt d) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Usunąć sprawę " + d.getCaseNumber() + "?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                try {
                    debtService.delete(d.getId());
                    loadDebts();
                } catch (IllegalArgumentException ex) {
                    // Sprawa z wpłatami jest nieusuwalna — komunikat wskazuje alternatywę
                    showAlert(Alert.AlertType.WARNING, "Nie usunięto sprawy", ex.getMessage());
                }
            }
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
