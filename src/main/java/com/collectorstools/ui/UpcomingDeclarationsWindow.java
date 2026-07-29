package com.collectorstools.ui;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.service.DebtService;
import com.collectorstools.service.DebtorService;
import com.collectorstools.service.DeclarationService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;

/**
 * Okno "lista zadań" — wszystkie oczekujące deklaracje posortowane chronologicznie.
 * Podwójne kliknięcie wiersza otwiera szczegóły dłużnika.
 */
public class UpcomingDeclarationsWindow extends Stage {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DeclarationService declarationService = new DeclarationService();
    private final DebtorService debtorService = new DebtorService();
    private final DebtService debtService = new DebtService();

    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    public UpcomingDeclarationsWindow() {
        setTitle("Nadchodzące deklaracje");
        setWidth(720);
        setHeight(520);
        setMinWidth(560);
        setMinHeight(360);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("detail-root");

        Text header = new Text("Oczekujące deklaracje");
        header.getStyleClass().add("detail-name");
        BorderPane.setMargin(header, new Insets(16, 16, 8, 16));
        root.setTop(header);

        buildTable();
        BorderPane.setMargin(table, new Insets(0, 16, 16, 16));
        root.setCenter(table);

        Scene scene = new Scene(root);
        try {
            var css = getClass().getResource("/styles.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        } catch (Exception ignored) {
        }
        setScene(scene);

        loadRows();
    }

    private void buildTable() {
        TableColumn<Row, String> dateCol = new TableColumn<>("Data");
        dateCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date()));
        dateCol.setPrefWidth(110);

        TableColumn<Row, String> debtorCol = new TableColumn<>("Dłużnik");
        debtorCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debtorName()));
        debtorCol.setPrefWidth(220);

        TableColumn<Row, String> amountCol = new TableColumn<>("Kwota");
        amountCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount()));
        amountCol.setPrefWidth(140);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Row, String> caseCol = new TableColumn<>("Nr sprawy");
        caseCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().caseNumber()));
        caseCol.setPrefWidth(150);

        table.getColumns().addAll(java.util.List.of(dateCol, debtorCol, amountCol, caseCol));
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Brak oczekujących deklaracji."));

        table.setRowFactory(tv -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openDebtor(row.getItem().debtor());
            });
            return row;
        });
    }

    private void loadRows() {
        rows.clear();
        for (Declaration d : declarationService.findByStatus(DeclarationStatus.OCZEKUJACA)) {
            Debtor debtor = debtorService.findById(d.getDebtorId()).orElse(null);
            String debtorName = debtor != null ? debtor.getFullName() : "Nieznany dłużnik";
            String caseNumber = d.getDebtId() != null
                    ? debtService.findById(d.getDebtId()).map(x -> x.getCaseNumber()).orElse("—")
                    : "—";
            String date = d.getPromisedDate() != null ? d.getPromisedDate().format(FMT) : "—";
            String amount = d.getPromisedAmount() + " PLN";
            rows.add(new Row(date, debtorName, amount, caseNumber, debtor));
        }
    }

    private void openDebtor(Debtor debtor) {
        if (debtor == null) return;
        DebtorDetailWindow detail = new DebtorDetailWindow(debtor);
        detail.setOnHidden(e -> loadRows());
        detail.show();
    }

    private record Row(String date, String debtorName, String amount, String caseNumber, Debtor debtor) {
    }
}
