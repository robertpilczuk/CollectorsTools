package com.collectorstools.ui;

import com.collectorstools.model.Debtor;
import com.collectorstools.service.DebtorService;
import com.collectorstools.util.PeselValidator;
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
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog importu dłużników z pliku CSV.
 * Kolumny (pozycyjnie): Nazwisko, Imię, PESEL, Telefon, Miasto — zgodne z eksportem.
 * Podgląd z checkboxami + walidacja (puste wymagane pola, błędny/duplikowany PESEL).
 */
public class CsvImportDialog extends Stage {

    private final DebtorService debtorService = new DebtorService();
    private final ObservableList<ImportRow> rows = FXCollections.observableArrayList();
    private final Label summary = new Label();
    private int importedCount = 0;

    public CsvImportDialog(File file) {
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Import dłużników z CSV");
        setWidth(720);
        setHeight(520);
        setMinWidth(560);

        buildRows(file);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setCenter(buildTable());
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root);
        try {
            var css = getClass().getResource("/styles.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        } catch (Exception ignored) {
        }
        setScene(scene);
    }

    public int getImportedCount() {
        return importedCount;
    }

    private TableView<ImportRow> buildTable() {
        TableView<ImportRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Plik nie zawiera danych do importu."));

        TableColumn<ImportRow, Boolean> selectCol = new TableColumn<>("Import");
        selectCol.setCellValueFactory(d -> d.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setEditable(true);
        selectCol.setPrefWidth(60);

        table.getColumns().add(selectCol);
        table.getColumns().add(textColumn("Nazwisko", r -> r.lastName, 120));
        table.getColumns().add(textColumn("Imię", r -> r.firstName, 110));
        table.getColumns().add(textColumn("PESEL", r -> r.pesel, 110));
        table.getColumns().add(textColumn("Telefon", r -> r.phone, 110));
        table.getColumns().add(textColumn("Miasto", r -> r.city, 110));

        TableColumn<ImportRow, String> statusCol = textColumn("Status", r -> r.status, 150);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(item.equals("OK")
                        ? "-fx-text-fill: #27ae60;"
                        : "-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
            }
        });
        table.getColumns().add(statusCol);
        return table;
    }

    private TableColumn<ImportRow, String> textColumn(String title, java.util.function.Function<ImportRow, String> getter, double width) {
        TableColumn<ImportRow, String> col = new TableColumn<>(title);
        col.setCellValueFactory(d -> new SimpleStringProperty(getter.apply(d.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    private HBox buildBottomBar() {
        refreshSummary();

        Button importBtn = new Button("Importuj zaznaczone");
        importBtn.getStyleClass().add("btn-primary");
        importBtn.setOnAction(e -> doImport());

        Button cancelBtn = new Button("Anuluj");
        cancelBtn.getStyleClass().add("btn-detail");
        cancelBtn.setOnAction(e -> { importedCount = 0; close(); });

        Region spacer = new Region();
        HBox bar = new HBox(8, summary, spacer, cancelBtn, importBtn);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 0, 0, 0));
        return bar;
    }

    private void refreshSummary() {
        long valid = rows.stream().filter(r -> r.valid).count();
        summary.setText("Wierszy: " + rows.size() + "   Poprawnych: " + valid
                + "   Odrzuconych: " + (rows.size() - valid));
    }

    private void doImport() {
        int count = 0;
        for (ImportRow r : rows) {
            if (r.isSelected() && r.valid) {
                debtorService.save(Debtor.builder()
                        .firstName(r.firstName)
                        .lastName(r.lastName)
                        .pesel(blankToNull(r.pesel))
                        .phone(blankToNull(r.phone))
                        .city(blankToNull(r.city))
                        .build());
                count++;
            }
        }
        importedCount = count;
        close();
    }

    // ===== Parsowanie + walidacja =====

    private void buildRows(File file) {
        List<String[]> parsed;
        try {
            parsed = parseCsv(file);
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Nie udało się odczytać pliku: " + ex.getMessage()).showAndWait();
            return;
        }

        Set<String> existingPesels = new HashSet<>();
        for (Debtor d : debtorService.findAll()) {
            if (d.getPesel() != null && !d.getPesel().isBlank()) existingPesels.add(d.getPesel());
        }
        Set<String> seenInFile = new HashSet<>();

        boolean first = true;
        for (String[] cols : parsed) {
            String lastName = at(cols, 0);
            String firstName = at(cols, 1);
            // Pomiń wiersz nagłówka
            if (first) {
                first = false;
                if (lastName.equalsIgnoreCase("Nazwisko")) continue;
            }
            String pesel = at(cols, 2);
            String phone = at(cols, 3);
            String city = at(cols, 4);

            boolean valid = true;
            String status = "OK";
            if (firstName.isBlank() || lastName.isBlank()) {
                valid = false;
                status = "Brak imienia/nazwiska";
            } else if (!pesel.isBlank()) {
                if (!PeselValidator.isValid(pesel)) {
                    valid = false;
                    status = "Błędny PESEL";
                } else if (existingPesels.contains(pesel)) {
                    valid = false;
                    status = "Duplikat PESEL (baza)";
                } else if (!seenInFile.add(pesel)) {
                    valid = false;
                    status = "Duplikat PESEL (plik)";
                }
            }
            rows.add(new ImportRow(lastName, firstName, pesel, phone, city, valid, status));
        }
    }

    private List<String[]> parseCsv(File file) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            char delimiter = ',';
            boolean delimiterDetected = false;
            while ((line = br.readLine()) != null) {
                line = stripBom(line);
                if (line.isBlank()) continue;
                if (!delimiterDetected) {
                    delimiter = detectDelimiter(line);
                    delimiterDetected = true;
                }
                result.add(parseLine(line, delimiter));
            }
        }
        return result;
    }

    private static char detectDelimiter(String line) {
        long commas = line.chars().filter(c -> c == ',').count();
        long semis = line.chars().filter(c -> c == ';').count();
        return semis > commas ? ';' : ',';
    }

    private static String[] parseLine(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static String at(String[] cols, int i) {
        return i < cols.length && cols[i] != null ? cols[i].trim() : "";
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == 0xFEFF) ? s.substring(1) : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Wiersz podglądu importu. */
    public static class ImportRow {
        private final BooleanProperty selected;
        final String lastName, firstName, pesel, phone, city, status;
        final boolean valid;

        ImportRow(String lastName, String firstName, String pesel, String phone, String city,
                  boolean valid, String status) {
            this.lastName = lastName;
            this.firstName = firstName;
            this.pesel = pesel;
            this.phone = phone;
            this.city = city;
            this.valid = valid;
            this.status = status;
            this.selected = new SimpleBooleanProperty(valid);
        }

        public BooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
    }
}
