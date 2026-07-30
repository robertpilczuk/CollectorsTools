package com.collectorstools.ui;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.service.DebtService;
import com.collectorstools.service.DebtorService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lista spraw, które wskazana osoba ma już w bazie.
 *
 * <p>Powtórzony PESEL w importowanym pliku nie jest błędem — w portfelu wierzyciela
 * 16 osób miało po kilka spraw. Sam komunikat „dłużnik już istnieje" nie wystarcza
 * do decyzji: użytkownik musi zobaczyć, co ta osoba już ma, żeby odróżnić kolejną
 * sprawę tego samego dłużnika od wiersza wysłanego drugi raz przez pomyłkę.
 */
public class ExistingCasesPane extends VBox {

    private final DebtorService debtorService;
    private final DebtService debtService;

    // Widoczne w pakiecie: test sprawdza treść komunikatów i zawartość listy
    final Label header = new Label();
    final Label fileNote = new Label();
    final ObservableList<Debt> cases = FXCollections.observableArrayList();
    private final TableView<Debt> table = new TableView<>(cases);

    public ExistingCasesPane() {
        this(new DebtorService(), new DebtService());
    }

    public ExistingCasesPane(DebtorService debtorService, DebtService debtService) {
        this.debtorService = debtorService;
        this.debtService = debtService;

        header.setWrapText(true);
        header.setStyle("-fx-font-weight: bold;");
        fileNote.setWrapText(true);
        fileNote.setStyle("-fx-text-fill: #e67e22;");
        fileNote.setManaged(false);
        fileNote.setVisible(false);

        table.getColumns().add(column("Nr sprawy", 110, debt ->
                debt.getCaseNumber() != null ? debt.getCaseNumber() : ""));
        table.getColumns().add(column("Wierzyciel", 140, debt ->
                debt.getCreditor() != null ? debt.getCreditor() : ""));
        table.getColumns().add(column("Portfel", 140, debt ->
                debt.getPortfolio() != null ? debt.getPortfolio() : ""));
        table.getColumns().add(column("Pozostało", 100, debt ->
                money(debt.getRemainingAmount()) + " zł"));
        table.getColumns().add(column("Stan", 150, debt ->
                debt.getStatus() != null ? debt.getStatus().getDisplayName() : ""));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(120);
        table.setPlaceholder(new Label("—"));

        setSpacing(6);
        setPadding(new Insets(8, 0, 0, 0));
        getChildren().addAll(header, fileNote, table);
        clear();
    }

    /**
     * Pokazuje sprawy osoby o podanym numerze PESEL.
     *
     * <p>Brak numeru albo brak takiej osoby w bazie nie jest sytuacją wyjątkową —
     * to zwykły przypadek nowego dłużnika, więc panel po prostu mówi, że nic nie znalazł.
     */
    public void showFor(String pesel) {
        showFor(pesel, 0);
    }

    /**
     * @param otherRowsInFile ile innych wierszy importowanego pliku ma ten sam PESEL —
     *                        one też trafią do tej samej osoby, a nie każdy do własnej
     */
    public void showFor(String pesel, int otherRowsInFile) {
        setFileNote(otherRowsInFile);
        if (pesel == null || pesel.isBlank()) {
            cases.clear();
            header.setText("Wiersz nie ma numeru PESEL — nie da się sprawdzić, czy ta osoba jest już w bazie.");
            return;
        }

        Optional<Debtor> debtor = debtorService.findByPesel(pesel);
        if (debtor.isEmpty()) {
            cases.clear();
            header.setText("PESEL " + pesel + " — brak takiej osoby w bazie, powstanie nowy dłużnik.");
            return;
        }

        Debtor found = debtor.get();
        List<Debt> existing = debtService.findByDebtorId(found.getId());
        cases.setAll(existing);
        header.setText(existing.isEmpty()
                ? found.getFullName() + " jest już w bazie, ale nie ma jeszcze żadnej sprawy."
                : found.getFullName() + " — sprawy już w bazie: " + existing.size()
                        + " (łącznie pozostało " + money(sumRemaining(existing)) + " zł)");
    }

    /** Czyści panel — nic nie jest zaznaczone. */
    public void clear() {
        cases.clear();
        setFileNote(0);
        header.setText("Zaznacz wiersz, aby zobaczyć sprawy tej osoby już zapisane w bazie.");
    }

    private void setFileNote(int otherRowsInFile) {
        boolean visible = otherRowsInFile > 0;
        fileNote.setText(visible
                ? "Ten sam PESEL ma jeszcze " + otherRowsInFile
                        + (otherRowsInFile == 1 ? " wiersz" : " wiersze/wierszy")
                        + " w importowanym pliku — wszystkie trafią do tej samej osoby."
                : "");
        fileNote.setManaged(visible);
        fileNote.setVisible(visible);
    }

    private static BigDecimal sumRemaining(List<Debt> debts) {
        return debts.stream()
                .map(debt -> debt.getRemainingAmount() != null ? debt.getRemainingAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TableColumn<Debt, String> column(String title, double width, Function<Debt, String> getter) {
        TableColumn<Debt, String> col = new TableColumn<>(title);
        col.setCellValueFactory(data -> new SimpleStringProperty(getter.apply(data.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value != null ? value : BigDecimal.ZERO;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
