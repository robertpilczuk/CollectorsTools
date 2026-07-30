package com.collectorstools.ui;

import com.collectorstools.importer.*;
import com.collectorstools.service.CaseImportService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Okno importu pliku XLSX.
 *
 * <p>Dwa kroki w jednym oknie: najpierw dopasowanie kolumn pliku do pól systemu,
 * potem podgląd odczytanych wierszy. Zapis następuje dopiero po zatwierdzeniu —
 * import, który po cichu odrzuca albo po cichu poprawia dane, jest gorszy od importu,
 * który się nie udał.
 */
public class XlsxImportDialog extends Stage {

    /** Data w nazwie pliku, np. „Zlecenie - Lubelskie 08.02.2017.xlsx". */
    private static final Pattern DATE_IN_NAME = Pattern.compile("(\\d{2})[.\\-_](\\d{2})[.\\-_](\\d{4})");

    /**
     * Wiersz z zaznaczeniem. Model importu celowo nie zna JavaFX — właściwość
     * obserwowalna żyje wyłącznie w warstwie interfejsu.
     */
    private static final class SelectableRow {
        private final ImportRow row;
        private final BooleanProperty selected;

        SelectableRow(ImportRow row) {
            this.row = row;
            // Wiersze odrzucone są odznaczone i pozostają takie — zaznaczenie
            // niczego nie zmieni, bo import i tak ich nie zapisze
            this.selected = new SimpleBooleanProperty(row.isImportable());
        }

        boolean isSelectedForImport() {
            return selected.get() && row.isImportable();
        }
    }

    private final File file;
    private final XlsxReader reader = new XlsxReader();
    private final CaseImportService importService = new CaseImportService();

    private final ObservableList<SelectableRow> rows = FXCollections.observableArrayList();
    private final TableView<ImportField> mappingTable = new TableView<>();
    private final TableView<SelectableRow> previewTable = new TableView<>();
    private final Label mappingStatus = new Label();
    private final Label previewSummary = new Label();
    private final DatePicker exportDatePicker = new DatePicker();
    private final ExistingCasesPane existingCases = new ExistingCasesPane();
    private final Button importButton = new Button("Importuj zaznaczone");

    private HeaderMapping mapping;
    private ImportSummary result;

    public XlsxImportDialog(File file) {
        this.file = file;

        initModality(Modality.APPLICATION_MODAL);
        setTitle("Import z pliku: " + file.getName());
        setWidth(1040);
        setHeight(680);
        setMinWidth(860);
        setMinHeight(520);

        List<String> headers = reader.readHeaders(file);
        mapping = HeaderMapping.autoDetect(headers);
        exportDatePicker.setValue(dateFromFileName(file.getName()));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setCenter(buildTabs());
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root);
        try {
            var css = getClass().getResource("/styles.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        } catch (Exception ignored) {
        }
        setScene(scene);

        refreshMappingStatus();
        reloadPreview();
    }

    /**
     * @return podsumowanie zapisu albo {@code null}, jeśli użytkownik zrezygnował
     */
    public ImportSummary getResult() {
        return result;
    }

    private TabPane buildTabs() {
        Tab mappingTab = new Tab("Dopasowanie kolumn", buildMappingPane());
        Tab previewTab = new Tab("Podgląd danych", buildPreviewPane());
        mappingTab.setClosable(false);
        previewTab.setClosable(false);
        return new TabPane(mappingTab, previewTab);
    }

    // ===== krok 1: dopasowanie kolumn =====

    private VBox buildMappingPane() {
        TableColumn<ImportField, String> fieldColumn = new TableColumn<>("Pole systemu");
        fieldColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getDisplayName() + (data.getValue().isRequired() ? " *" : "")));
        fieldColumn.setPrefWidth(220);

        TableColumn<ImportField, ImportField> columnChoice = new TableColumn<>("Kolumna w pliku");
        columnChoice.setCellValueFactory(data ->
                new javafx.beans.property.SimpleObjectProperty<>(data.getValue()));
        columnChoice.setPrefWidth(300);
        columnChoice.setCellFactory(column -> new TableCell<>() {
            private final ComboBox<String> combo = new ComboBox<>();

            @Override
            protected void updateItem(ImportField field, boolean empty) {
                super.updateItem(field, empty);
                if (empty || field == null) {
                    setGraphic(null);
                    return;
                }
                combo.getItems().setAll(withEmptyOption(mapping.getHeaders()));
                Integer assigned = mapping.columnOf(field);
                combo.setValue(assigned != null ? mapping.getHeaders().get(assigned) : "— pomiń —");
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.setOnAction(event -> {
                    String chosen = combo.getValue();
                    mapping.assign(field, "— pomiń —".equals(chosen)
                            ? null : mapping.getHeaders().indexOf(chosen));
                    refreshMappingStatus();
                    mappingTable.refresh();
                });
                setGraphic(combo);
            }
        });

        TableColumn<ImportField, String> requiredColumn = new TableColumn<>("Wymagane");
        requiredColumn.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().isRequired() ? "tak" : ""));
        requiredColumn.setPrefWidth(90);

        mappingTable.getColumns().addAll(List.of(fieldColumn, columnChoice, requiredColumn));
        mappingTable.setItems(FXCollections.observableArrayList(ImportField.values()));
        mappingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        mappingStatus.setWrapText(true);

        Label exportDateLabel = new Label("Data sporządzenia pliku:");
        exportDateLabel.setMinWidth(170);
        Label exportDateHint = new Label(
                "Służy do ustalenia roku w terminach zapisanych bez roku, np. „31,12\".");
        exportDateHint.setStyle("-fx-text-fill: #7f8c8d;");
        exportDateHint.setWrapText(true);

        HBox dateRow = new HBox(8, exportDateLabel, exportDatePicker);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        Button reload = new Button("Zastosuj i odśwież podgląd");
        reload.getStyleClass().add("btn-detail");
        reload.setOnAction(event -> reloadPreview());

        VBox pane = new VBox(10, mappingStatus, mappingTable, dateRow, exportDateHint, reload);
        pane.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(mappingTable, Priority.ALWAYS);
        return pane;
    }

    private List<String> withEmptyOption(List<String> headers) {
        List<String> options = new ArrayList<>();
        options.add("— pomiń —");
        options.addAll(headers);
        return options;
    }

    private void refreshMappingStatus() {
        List<ImportField> missing = mapping.missingRequiredFields();
        if (missing.isEmpty()) {
            List<String> unmapped = mapping.unmappedHeaders();
            mappingStatus.setText(unmapped.isEmpty()
                    ? "Wszystkie kolumny pliku zostały dopasowane."
                    : "Dopasowanie kompletne. Kolumny pominięte (ich zawartość nie zostanie zapisana): "
                            + String.join(", ", unmapped));
            mappingStatus.setStyle("-fx-text-fill: #27ae60;");
        } else {
            mappingStatus.setText("Brakuje dopasowania pól wymaganych: "
                    + missing.stream().map(ImportField::getDisplayName).toList()
                    + ". Wskaż kolumny ręcznie — bez nich import nie jest możliwy.");
            mappingStatus.setStyle("-fx-text-fill: #e74c3c;");
        }
        updateImportButton();
    }

    // ===== krok 2: podgląd danych =====

    private VBox buildPreviewPane() {
        TableColumn<SelectableRow, Boolean> selectColumn = new TableColumn<>("Import");
        selectColumn.setCellValueFactory(data -> data.getValue().selected);
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);
        selectColumn.setPrefWidth(60);

        previewTable.getColumns().add(selectColumn);
        previewTable.getColumns().add(textColumn("Wiersz", 60,
                row -> String.valueOf(row.row.getSourceRowNumber())));
        previewTable.getColumns().add(textColumn("Dłużnik", 170, row -> row.row.getFullName()));
        previewTable.getColumns().add(textColumn("PESEL", 100,
                row -> row.row.getPesel() != null ? row.row.getPesel() : ""));
        previewTable.getColumns().add(textColumn("Nr sprawy", 90,
                row -> row.row.getCaseNumber() != null ? row.row.getCaseNumber() : ""));
        previewTable.getColumns().add(textColumn("Saldo", 100,
                row -> money(row.row.getBalance()) + " zł"));
        previewTable.getColumns().add(textColumn("Z komentarza", 150, this::describeParsed));

        TableColumn<SelectableRow, String> statusColumn =
                textColumn("Status", 260, row -> row.row.getStatusDescription());
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                SelectableRow row = (getTableRow() == null) ? null : (SelectableRow) getTableRow().getItem();
                if (empty || item == null || row == null) {
                    setText(null);
                    setStyle("");
                    setTooltip(null);
                    return;
                }
                setText(item);
                setTooltip(buildTooltip(row.row));
                if (!row.row.isImportable()) {
                    setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                } else if (!row.row.getWarnings().isEmpty()) {
                    setStyle("-fx-text-fill: #e67e22;");
                } else {
                    setStyle("-fx-text-fill: #27ae60;");
                }
            }
        });
        previewTable.getColumns().add(statusColumn);

        previewTable.setItems(rows);
        previewTable.setEditable(true);
        previewTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        previewTable.setPlaceholder(new Label("Plik nie zawiera danych do zaimportowania."));

        Button selectAll = new Button("Zaznacz poprawne");
        selectAll.getStyleClass().add("btn-detail");
        selectAll.setOnAction(event -> setAllSelected(true));

        Button clearAll = new Button("Odznacz wszystkie");
        clearAll.getStyleClass().add("btn-detail");
        clearAll.setOnAction(event -> setAllSelected(false));

        HBox toolbar = new HBox(8, selectAll, clearAll);
        toolbar.setPadding(new Insets(8, 0, 8, 0));

        previewTable.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, current) -> showExistingCases(current));

        VBox pane = new VBox(toolbar, previewTable, existingCases);
        VBox.setVgrow(previewTable, Priority.ALWAYS);
        return pane;
    }

    /**
     * Pokazuje, co zaznaczona osoba ma już w bazie i ile razy występuje w pliku.
     * Kolejna sprawa tego samego dłużnika wygląda w podglądzie tak samo jak wiersz
     * wysłany drugi raz przez pomyłkę — rozróżnia je dopiero ta lista.
     */
    private void showExistingCases(SelectableRow selected) {
        if (selected == null) {
            existingCases.clear();
            return;
        }
        String pesel = selected.row.getPesel();
        existingCases.showFor(pesel, Math.max(0, countSamePeselInFile(pesel) - 1));
    }

    private int countSamePeselInFile(String pesel) {
        if (pesel == null || pesel.isBlank()) {
            return 0;
        }
        return (int) rows.stream().filter(row -> pesel.equals(row.row.getPesel())).count();
    }

    private Tooltip buildTooltip(ImportRow row) {
        List<String> lines = new ArrayList<>(row.getErrors());
        lines.addAll(row.getWarnings());
        return lines.isEmpty() ? null : new Tooltip(String.join("\n", lines));
    }

    /**
     * Skrót informacji wyciągniętych z komentarza — to one najczęściej budzą wątpliwości.
     */
    private String describeParsed(SelectableRow selectable) {
        ParsedComment comment = selectable.row.getComment();
        if (comment == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (comment.hasLastPayment()) {
            parts.add("wpłata " + money(comment.lastPaymentAmount()));
        }
        if (comment.hasPromise() && comment.promise().confidence() == ParseConfidence.HIGH) {
            parts.add("deklaracja " + money(comment.promise().amount()));
        } else if (comment.hasPromise()) {
            parts.add("obietnica (?)");
        }
        return String.join(", ", parts);
    }

    private TableColumn<SelectableRow, String> textColumn(
            String title, double width, java.util.function.Function<SelectableRow, String> getter) {
        TableColumn<SelectableRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(getter.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private void setAllSelected(boolean selected) {
        rows.forEach(row -> row.selected.set(selected && row.row.isImportable()));
        updateImportButton();
    }

    private void reloadPreview() {
        existingCases.clear();
        if (!mapping.isReady()) {
            rows.clear();
            previewSummary.setText("Uzupełnij dopasowanie kolumn, aby zobaczyć podgląd.");
            updateImportButton();
            return;
        }

        LocalDate reference = exportDatePicker.getValue() != null
                ? exportDatePicker.getValue() : LocalDate.now();

        ErrorHandler.run("Odczyt pliku", () -> {
            List<ImportRow> parsed = reader.readRows(file, mapping, reference);
            importService.markExistingDebtors(parsed);

            rows.setAll(parsed.stream().map(SelectableRow::new).toList());
            refreshPreviewSummary();
        });
        updateImportButton();
    }

    private void refreshPreviewSummary() {
        long importable = rows.stream().filter(row -> row.row.isImportable()).count();
        long withWarnings = rows.stream()
                .filter(row -> row.row.isImportable() && !row.row.getWarnings().isEmpty()).count();
        long existing = rows.stream().filter(row -> row.row.isExistingDebtor()).count();

        previewSummary.setText("Wierszy: " + rows.size()
                + "   poprawnych: " + importable
                + "   odrzuconych: " + (rows.size() - importable)
                + "   z zastrzeżeniami: " + withWarnings
                + "   kolejnych spraw istniejących dłużników: " + existing);
    }

    private void updateImportButton() {
        long selected = rows.stream().filter(SelectableRow::isSelectedForImport).count();
        importButton.setDisable(!mapping.isReady() || selected == 0);
        importButton.setText(selected > 0
                ? "Importuj zaznaczone (" + selected + ")" : "Importuj zaznaczone");
    }

    // ===== pasek dolny =====

    private HBox buildBottomBar() {
        previewSummary.setWrapText(true);

        importButton.getStyleClass().add("btn-primary");
        importButton.setOnAction(event -> runImport());

        Button cancel = new Button("Anuluj");
        cancel.getStyleClass().add("btn-detail");
        cancel.setOnAction(event -> close());

        Region spacer = new Region();
        HBox bar = new HBox(8, previewSummary, spacer, cancel, importButton);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 0, 0, 0));

        // Zaznaczenia zmieniają się w tabeli, więc licznik odświeżamy przy każdym kliknięciu
        previewTable.setOnMouseClicked(event -> updateImportButton());
        return bar;
    }

    private void runImport() {
        List<ImportRow> selected = rows.stream()
                .filter(SelectableRow::isSelectedForImport)
                .map(row -> row.row)
                .toList();

        boolean succeeded = ErrorHandler.run("Import danych",
                () -> result = importService.importRows(selected, file.getName()));
        if (!succeeded) {
            return;
        }

        Alert done = new Alert(result.getFailures().isEmpty()
                ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        done.setTitle("Import zakończony");
        done.setHeaderText("Plik: " + file.getName());
        done.setContentText(result.describe());
        done.getDialogPane().setMinWidth(520);

        if (!result.getFailures().isEmpty()) {
            TextArea failures = new TextArea(String.join("\n", result.getFailures()));
            failures.setEditable(false);
            done.getDialogPane().setExpandableContent(failures);
        }
        done.showAndWait();
        close();
    }

    /**
     * Data z nazwy pliku jako domyślna data sporządzenia eksportu — nazwy w rodzaju
     * „Zlecenie - Lubelskie 08.02.2017.xlsx" niosą ją wprost, a użytkownik i tak
     * może ją poprawić.
     */
    static LocalDate dateFromFileName(String fileName) {
        Matcher matcher = DATE_IN_NAME.matcher(fileName);
        if (matcher.find()) {
            try {
                return LocalDate.of(Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
            } catch (Exception ignored) {
                // nieprawidłowa data w nazwie — użyjemy dzisiejszej
            }
        }
        return LocalDate.now();
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value != null ? value : BigDecimal.ZERO;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
