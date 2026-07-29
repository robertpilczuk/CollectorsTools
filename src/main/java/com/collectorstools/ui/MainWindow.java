package com.collectorstools.ui;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.service.DebtService;
import com.collectorstools.service.DebtorService;
import com.collectorstools.service.DeclarationService;
import com.collectorstools.service.InteractionService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MainWindow {

    private static final String ALL_CITIES = "— wszystkie miasta —";

    private final DebtorService debtorService = new DebtorService();
    private final DebtService debtService = new DebtService();
    private final DeclarationService declarationService = new DeclarationService();
    private final InteractionService interactionService = new InteractionService();

    private final BorderPane root = new BorderPane();
    private final TableView<Debtor> debtorTable = new TableView<>();
    private final ObservableList<Debtor> debtorList = FXCollections.observableArrayList();
    private final TextField searchField = new TextField();
    private final ComboBox<String> cityFilter = new ComboBox<>();
    private final TextField creditorFilter = new TextField();

    private final Label debtorsStat = new Label();
    private final Label remainingStat = new Label();
    private final Label pendingStat = new Label();
    private final Label keptStat = new Label();

    public MainWindow() {
        buildUI();
        loadDebtors();
    }

    public BorderPane getRoot() {
        return root;
    }

    private void buildUI() {
        root.getStyleClass().add("main-root");

        // --- Górny pasek + statystyki ---
        VBox top = new VBox(buildTopBar(), buildStatsBar());
        root.setTop(top);

        // --- Tabela dłużników ---
        buildTable();
        VBox center = new VBox(10, buildSearchBar(), debtorTable);
        center.setPadding(new Insets(16));
        VBox.setVgrow(debtorTable, Priority.ALWAYS);
        root.setCenter(center);
    }

    private HBox buildTopBar() {
        Text title = new Text("CollectorsTools");
        title.getStyleClass().add("app-title");

        MenuButton dbMenu = new MenuButton("Baza danych");
        dbMenu.getStyleClass().add("btn-detail");
        MenuItem backupItem = new MenuItem("Utwórz kopię zapasową");
        backupItem.setOnAction(e -> backupDatabase());
        MenuItem restoreItem = new MenuItem("Przywróć z kopii");
        restoreItem.setOnAction(e -> restoreDatabase());
        dbMenu.getItems().addAll(backupItem, restoreItem);

        Button upcomingBtn = new Button("Nadchodzące deklaracje");
        upcomingBtn.getStyleClass().add("btn-detail");
        upcomingBtn.setOnAction(e -> new UpcomingDeclarationsWindow().show());

        Button exportBtn = new Button("Eksportuj CSV");
        exportBtn.getStyleClass().add("btn-detail");
        exportBtn.setOnAction(e -> exportToCsv());

        Button importBtn = new Button("Importuj CSV");
        importBtn.getStyleClass().add("btn-detail");
        importBtn.setOnAction(e -> importFromCsv());

        Button addBtn = new Button("+ Dodaj dłużnika");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openDebtorForm(null));

        HBox bar = new HBox(8, title, new Pane(), dbMenu, upcomingBtn, exportBtn, importBtn, addBtn);
        HBox.setHgrow(bar.getChildren().get(1), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private HBox buildStatsBar() {
        for (Label l : new Label[]{debtorsStat, remainingStat, pendingStat, keptStat}) {
            l.getStyleClass().add("stat-label");
        }
        HBox bar = new HBox(24, debtorsStat, remainingStat, pendingStat, keptStat);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("stats-bar");
        return bar;
    }

    private void refreshStats() {
        long debtors = debtorService.count();
        BigDecimal remaining = debtService.sumAllRemaining();
        long pending = declarationService.countByStatus(DeclarationStatus.OCZEKUJACA);
        long kept = declarationService.countByStatus(DeclarationStatus.DOTRZYMANA);
        long broken = declarationService.countByStatus(DeclarationStatus.ZLAMANA);
        long finalized = kept + broken;
        double keptPct = finalized == 0 ? 0.0 : (kept * 100.0 / finalized);

        debtorsStat.setText("Dłużnicy: " + debtors);
        remainingStat.setText(String.format("Suma długów: %,.2f PLN", remaining));
        pendingStat.setText("Oczekujące deklaracje: " + pending);
        keptStat.setText(String.format("Dotrzymane deklaracje: %.0f%%", keptPct));
    }

    private HBox buildSearchBar() {
        searchField.setPromptText("Szukaj dłużnika (imię, nazwisko)...");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        cityFilter.setPromptText("Miasto");
        cityFilter.setPrefWidth(180);
        cityFilter.valueProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        creditorFilter.setPromptText("Wierzyciel...");
        creditorFilter.setPrefWidth(180);
        creditorFilter.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        Button clearBtn = new Button("✕");
        clearBtn.getStyleClass().add("btn-clear");
        clearBtn.setOnAction(e -> resetFilters());

        HBox bar = new HBox(8, searchField, cityFilter, creditorFilter, clearBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        TableColumn<Debtor, String> nameCol = new TableColumn<>("Nazwisko i imię");
        nameCol.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        data.getValue().getLastName() + " " + data.getValue().getFirstName()));
        nameCol.setPrefWidth(200);
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                Debtor debtor = (getTableRow() == null) ? null : getTableRow().getItem();
                if (empty || name == null || debtor == null) {
                    setText(null);
                    setStyle("");
                } else if (debtor.isUrgent()) {
                    setText("⚠ " + name);
                    setStyle("-fx-text-fill: #d32f2f; -fx-font-weight: bold;");
                } else {
                    setText(name);
                    setStyle("");
                }
            }
        });

        TableColumn<Debtor, String> peselCol = new TableColumn<>("PESEL");
        peselCol.setCellValueFactory(new PropertyValueFactory<>("pesel"));
        peselCol.setPrefWidth(120);

        TableColumn<Debtor, String> phoneCol = new TableColumn<>("Telefon");
        phoneCol.setCellValueFactory(new PropertyValueFactory<>("phone"));
        phoneCol.setPrefWidth(130);

        TableColumn<Debtor, String> cityCol = new TableColumn<>("Miasto");
        cityCol.setCellValueFactory(new PropertyValueFactory<>("city"));
        cityCol.setPrefWidth(130);

        TableColumn<Debtor, String> interactionsCol = new TableColumn<>("Kontakty");
        interactionsCol.setCellValueFactory(data -> {
            int count = interactionService.countByDebtorId(data.getValue().getId());
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(count));
        });
        interactionsCol.setPrefWidth(80);
        interactionsCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<Debtor, String> brokenCol = new TableColumn<>("Złamane dekl.");
        brokenCol.setCellValueFactory(data -> {
            long count = declarationService.countBrokenByDebtorId(data.getValue().getId());
            return new javafx.beans.property.SimpleStringProperty(count > 0 ? "⚠ " + count : "–");
        });
        brokenCol.setPrefWidth(110);
        brokenCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<Debtor, Void> actionsCol = new TableColumn<>("Akcje");
        actionsCol.setPrefWidth(200);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            final Button detailBtn = new Button("Szczegóły");
            final Button editBtn   = new Button("Edytuj");
            final Button deleteBtn = new Button("Usuń");
            {
                detailBtn.getStyleClass().add("btn-detail");
                editBtn.getStyleClass().add("btn-edit");
                deleteBtn.getStyleClass().add("btn-danger");

                detailBtn.setOnAction(e -> openDebtorDetail(getTableView().getItems().get(getIndex())));
                editBtn.setOnAction(e -> openDebtorForm(getTableView().getItems().get(getIndex())));
                deleteBtn.setOnAction(e -> confirmDelete(getTableView().getItems().get(getIndex())));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                HBox box = new HBox(6, detailBtn, editBtn, deleteBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });

        debtorTable.getColumns().addAll(nameCol, peselCol, phoneCol, cityCol, interactionsCol, brokenCol, actionsCol);
        debtorTable.setItems(debtorList);
        debtorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        debtorTable.getStyleClass().add("debtor-table");
        debtorTable.setPlaceholder(new Label("Brak dłużników. Kliknij '+ Dodaj dłużnika'."));

        // Podwójne kliknięcie = szczegóły
        debtorTable.setRowFactory(tv -> {
            TableRow<Debtor> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openDebtorDetail(row.getItem());
            });
            return row;
        });
    }

    private void loadDebtors() {
        refreshCityFilter();
        applyFilters();
        refreshStats();
    }

    /**
     * Odświeża listę miast w dropdownie, zachowując aktualny wybór jeśli nadal istnieje.
     */
    private void refreshCityFilter() {
        String current = cityFilter.getValue();
        cityFilter.getItems().setAll(ALL_CITIES);
        cityFilter.getItems().addAll(debtorService.findDistinctCities());
        cityFilter.setValue(current != null && cityFilter.getItems().contains(current) ? current : ALL_CITIES);
    }

    /**
     * Łączy filtry: wyszukiwanie po nazwisku/imieniu, miasto i wierzyciel.
     */
    private void applyFilters() {
        List<Debtor> result = debtorService.search(searchField.getText());

        String city = cityFilter.getValue();
        if (city != null && !city.equals(ALL_CITIES)) {
            result = result.stream()
                    .filter(d -> city.equalsIgnoreCase(d.getCity()))
                    .collect(Collectors.toList());
        }

        String creditor = creditorFilter.getText();
        if (creditor != null && !creditor.isBlank()) {
            Set<Long> ids = debtService.findDebtorIdsByCreditor(creditor.trim());
            result = result.stream()
                    .filter(d -> ids.contains(d.getId()))
                    .collect(Collectors.toList());
        }

        debtorList.setAll(result);
    }

    private void resetFilters() {
        searchField.clear();
        creditorFilter.clear();
        cityFilter.setValue(ALL_CITIES);
        applyFilters();
    }

    private void openDebtorForm(Debtor debtor) {
        DebtorFormDialog dialog = new DebtorFormDialog(debtor);
        dialog.showAndWait().ifPresent(result -> {
            if (debtor == null) debtorService.save(result);
            else { result.setId(debtor.getId()); debtorService.update(result); }
            loadDebtors();
        });
    }

    private void openDebtorDetail(Debtor debtor) {
        DebtorDetailWindow detailWindow = new DebtorDetailWindow(debtor);
        detailWindow.show();
        detailWindow.setOnHidden(e -> loadDebtors());
    }

    /**
     * Eksportuje aktualnie widoczną (przefiltrowaną) listę dłużników do pliku CSV.
     */
    private void exportToCsv() {
        if (debtorList.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Eksport CSV",
                    "Lista dłużników jest pusta — nie ma czego eksportować.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Eksportuj listę dłużników do CSV");
        chooser.setInitialFileName("dluznicy.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pliki CSV", "*.csv"));
        File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;

        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("\\uFEFF"); // BOM - poprawne polskie znaki w Excelu
            w.write(String.join(",", "Nazwisko", "Imię", "PESEL", "Telefon", "Miasto",
                    "Liczba kontaktów", "Złamane deklaracje"));
            w.write("\n");

            for (Debtor d : debtorList) {
                int contacts = interactionService.countByDebtorId(d.getId());
                long broken = declarationService.countBrokenByDebtorId(d.getId());
                w.write(String.join(",",
                        csvField(d.getLastName()),
                        csvField(d.getFirstName()),
                        csvField(d.getPesel()),
                        csvField(d.getPhone()),
                        csvField(d.getCity()),
                        String.valueOf(contacts),
                        String.valueOf(broken)));
                w.write("\n");
            }

            showAlert(Alert.AlertType.INFORMATION, "Eksport zakończony",
                    "Zapisano " + debtorList.size() + " dłużników do:\n" + file.getAbsolutePath());
        } catch (IOException ex) {
            showAlert(Alert.AlertType.ERROR, "Błąd eksportu", ex.getMessage());
        }
    }

    /**
     * Kopia zapasowa — kopiuje plik bazy do wybranej lokalizacji (data w nazwie).
     */
    private void backupDatabase() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Utwórz kopię zapasową bazy");
        chooser.setInitialFileName("collectorstools_backup_" + stamp + ".db");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Baza SQLite", "*.db"));
        File target = chooser.showSaveDialog(root.getScene().getWindow());
        if (target == null) return;

        try {
            Path source = Path.of(DatabaseManager.getInstance().getDbPath());
            Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            showAlert(Alert.AlertType.INFORMATION, "Kopia zapasowa",
                    "Zapisano kopię bazy do:\n" + target.getAbsolutePath());
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Błąd kopii zapasowej", ex.getMessage());
        }
    }

    /**
     * Przywrócenie — nadpisuje bieżącą bazę wybraną kopią i zamyka aplikację (wymaga restartu).
     */
    private void restoreDatabase() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Przywróć bazę z kopii");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Baza SQLite", "*.db"));
        File source = chooser.showOpenDialog(root.getScene().getWindow());
        if (source == null) return;

        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Przywrócenie nadpisze bieżącą bazę danych — tej operacji nie można cofnąć.\n"
                        + "Po przywróceniu aplikacja zostanie zamknięta. Uruchom ją ponownie.\n\nKontynuować?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Przywróć z kopii");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        try {
            Path dbPath = Path.of(DatabaseManager.getInstance().getDbPath());
            DatabaseManager.getInstance().close();
            Files.copy(source.toPath(), dbPath, StandardCopyOption.REPLACE_EXISTING);
            showAlert(Alert.AlertType.INFORMATION, "Przywrócono",
                    "Baza została przywrócona. Aplikacja zostanie teraz zamknięta — uruchom ją ponownie.");
            Platform.exit();
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Błąd przywracania", ex.getMessage());
        }
    }

    /**
     * Import dłużników z pliku CSV przez dialog podglądu z walidacją.
     */
    private void importFromCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Importuj dłużników z CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pliki CSV", "*.csv"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;

        CsvImportDialog dialog = new CsvImportDialog(file);
        dialog.showAndWait();
        if (dialog.getImportedCount() > 0) {
            loadDebtors();
            showAlert(Alert.AlertType.INFORMATION, "Import zakończony",
                    "Zaimportowano " + dialog.getImportedCount() + " dłużników.");
        }
    }

    private String csvField(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void confirmDelete(Debtor debtor) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Usuń dłużnika");
        alert.setHeaderText("Czy na pewno usunąć dłużnika?");
        alert.setContentText(debtor.getFullName() + "\nUsunięcie spowoduje usunięcie wszystkich deklaracji i interakcji.");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                debtorService.delete(debtor.getId());
                loadDebtors();
            }
        });
    }
}
