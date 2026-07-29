package com.collectorstools.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Buduje arkusz XLSX w pamięci na potrzeby testów importu.
 *
 * <p>Testy nie mogą korzystać z pliku klienta: zawiera dane osobowe i nie należy
 * do repozytorium. Układ kolumn odwzorowuje jednak jego strukturę, żeby testy
 * sprawdzały ten sam kształt danych, z jakim importer ma do czynienia.
 */
public final class TestWorkbook {

    /** Nagłówki zgodne z rzeczywistym eksportem wierzyciela. */
    public static final List<String> HEADERS = List.of(
            "Nr sprawy", "czy była wizyta terenowa", "Nazwa dłużnika", "PESEL", "Kod pocztowy",
            "Miejscowość", "Ulica", "Nr domu", "Nr lokalu", "Adres 2", "Adres 3",
            "Saldo aktualne", "Kapitał", "Odsetki", "Koszty", "Wierzyciel pierwotny", "Portfel",
            "Wierzyciel obecny", "Sygnatura sądowa", "Sąd", "Telefony", "Konto", "produkt",
            "Data rozpoczęcia obsługi", "Komentarz do sprawy", "notatka z wizyty");

    private TestWorkbook() {
    }

    /**
     * Zapisuje arkusz z podanymi wierszami danych.
     *
     * @param rows wiersze; wartości liczbowe zapisywane są jako liczby, reszta jako tekst
     */
    public static File write(Path directory, String name, List<Object[]> rows) throws Exception {
        File file = directory.resolve(name).toFile();

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(file)) {

            Sheet sheet = workbook.createSheet("Arkusz1");

            Row header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                header.createCell(column).setCellValue(HEADERS.get(column));
            }

            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                Object[] values = rows.get(index);
                for (int column = 0; column < values.length; column++) {
                    Object value = values[column];
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number number) {
                        row.createCell(column).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(column).setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
        }
        return file;
    }

    /**
     * Wiersz odwzorowujący typową sprawę z eksportu.
     */
    public static Object[] row(String caseNumber, String visit, String name, String pesel,
                               double balance, double principal, double interest, double costs,
                               String creditor, String phones, String comment) {
        Object[] values = new Object[HEADERS.size()];
        values[0] = caseNumber;
        values[1] = visit;
        values[2] = name;
        values[3] = pesel;
        values[4] = "20-122";
        values[5] = "LUBLIN";
        values[6] = "Cyrulicza";
        values[7] = "2";
        values[8] = "9";
        values[11] = balance;
        values[12] = principal;
        values[13] = interest;
        values[14] = costs;
        values[16] = "ALIOR_ZLEC_024";
        values[17] = creditor;
        values[20] = phones;
        values[21] = "55171000070000001674070150";
        values[22] = "KREDYT GOTÓWKOWY WALMED";
        values[23] = "2016-11-15 00:00:00.000";
        values[24] = comment;
        return values;
    }
}
