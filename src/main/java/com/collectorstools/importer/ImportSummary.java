package com.collectorstools.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Wynik importu — co powstało, co pominięto i dlaczego.
 *
 * <p>Liczniki uzupełnia {@code CaseImportService} w trakcie zapisu; odczyt służy
 * do pokazania podsumowania po zakończeniu importu.
 */
public class ImportSummary {

    private int rowsImported;
    private int rowsSkipped;
    private int debtorsCreated;
    private int debtorsReused;
    private int debtsCreated;
    private int phonesCreated;
    private int interactionsCreated;
    private int declarationsCreated;
    private int paymentsRecorded;

    private final List<String> failures = new ArrayList<>();

    public void countImportedRow() {
        rowsImported++;
    }

    public void countSkippedRow() {
        rowsSkipped++;
    }

    public void countDebtorCreated() {
        debtorsCreated++;
    }

    public void countDebtorReused() {
        debtorsReused++;
    }

    public void countDebt() {
        debtsCreated++;
    }

    public void countPhones(int count) {
        phonesCreated += count;
    }

    public void countInteraction() {
        interactionsCreated++;
    }

    public void countDeclaration() {
        declarationsCreated++;
    }

    public void countPayment() {
        paymentsRecorded++;
    }

    public void addFailure(int sourceRow, String reason) {
        failures.add("Wiersz " + sourceRow + ": " + reason);
        rowsSkipped++;
    }

    public int getRowsImported() {
        return rowsImported;
    }

    public int getRowsSkipped() {
        return rowsSkipped;
    }

    public int getDebtorsCreated() {
        return debtorsCreated;
    }

    public int getDebtorsReused() {
        return debtorsReused;
    }

    public int getDebtsCreated() {
        return debtsCreated;
    }

    public int getPhonesCreated() {
        return phonesCreated;
    }

    public int getInteractionsCreated() {
        return interactionsCreated;
    }

    public int getDeclarationsCreated() {
        return declarationsCreated;
    }

    public int getPaymentsRecorded() {
        return paymentsRecorded;
    }

    public List<String> getFailures() {
        return List.copyOf(failures);
    }

    /**
     * Podsumowanie do pokazania po zakończeniu importu.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("Zaimportowano wierszy: ").append(rowsImported);
        if (rowsSkipped > 0) {
            text.append("   pominięto: ").append(rowsSkipped);
        }
        text.append("\n\nUtworzono:\n");
        text.append("  • dłużnicy: ").append(debtorsCreated);
        if (debtorsReused > 0) {
            text.append("  (kolejne sprawy istniejących: ").append(debtorsReused).append(")");
        }
        text.append("\n  • sprawy: ").append(debtsCreated);
        text.append("\n  • numery telefonu: ").append(phonesCreated);
        text.append("\n  • kontakty: ").append(interactionsCreated);
        text.append("\n  • deklaracje: ").append(declarationsCreated);
        text.append("\n  • wpłaty historyczne: ").append(paymentsRecorded);
        return text.toString();
    }
}
