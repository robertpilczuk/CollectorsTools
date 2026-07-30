package com.collectorstools.demo;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sprawdza, czy plik demonstracyjny nie zachował danych z pliku źródłowego.
 *
 * <p>Kontrola jest osobnym narzędziem, a nie testem, bo wymaga obu plików — a źródłowy
 * nigdy nie trafia do repozytorium. Uruchamia się ją ręcznie po każdym wygenerowaniu
 * danych demo; testy anonimizatora sprawdzają reguły, ta kontrola sprawdza wynik.
 *
 * <p>Porównanie jest <b>wyrównane do wiersza</b>. Nazwisko z puli zastępczej może przypadkiem
 * pokryć się z nazwiskiem innej osoby z pliku i niczego to nie ujawnia; dopiero ta sama
 * wartość w tym samym wierszu oznacza, że oryginał przeżył anonimizację.
 *
 * <p>Uruchomienie:
 * <pre>
 *   java -cp target/classes:… com.collectorstools.demo.LeakCheck \
 *        "data/Zlecenie - Lubelskie 08.02.2017.xlsx" \
 *        src/main/resources/demo/zlecenie-demo.xlsx
 * </pre>
 */
public final class LeakCheck {

    /** Sprawdzane pole pliku źródłowego: nazwa i numer kolumny. */
    private record Field(String name, int column) {
    }

    private static final List<Field> FIELDS = List.of(
            new Field("nr sprawy", 0), new Field("nazwa dłużnika", 2), new Field("PESEL", 3),
            new Field("kod pocztowy", 4), new Field("ulica", 6), new Field("nr domu", 7),
            new Field("nr lokalu", 8), new Field("adres 2", 9), new Field("telefony", 20),
            new Field("konto", 21), new Field("komentarz — pracodawca", 24));

    /**
     * Miejscowość zostaje w danych demo świadomie: nie wskazuje osoby, a bez niej znika
     * rozkład geograficzny portfela. Adresy wiejskie powtarzają nazwę miejscowości w polu
     * ulicy, więc bez tego wyłączenia kontrola zgłaszałaby ją jako przeciek.
     */
    private static final int CITY_COLUMN = 5;

    /** Etykiety struktury komentarza i wypełniacze — zostają celowo, nie są danymi osoby. */
    private static final List<String> KEPT_BY_DESIGN = List.of(
            "miejsce zatrudnienia", "adres zatrudnienia", "tel. do pracy", "tel",
            "nie dotyczy", "null", "brak");

    /** Krótsze fragmenty pasowałyby przypadkiem do zbyt wielu miejsc. */
    private static final int MIN_TOKEN_LENGTH = 4;

    private LeakCheck() {
    }

    /** Znaleziony przeciek: pole źródłowe, numer wiersza i zamaskowana wartość. */
    public record Leak(String field, int sourceRow, String maskedValue) {
        @Override
        public String toString() {
            return field + " [wiersz " + sourceRow + "]: " + maskedValue;
        }
    }

    public static List<Leak> check(File source, File demo) {
        List<Leak> leaks = new ArrayList<>();

        try (Workbook sourceBook = WorkbookFactory.create(source, null, true);
             Workbook demoBook = WorkbookFactory.create(demo, null, true)) {

            Sheet sourceSheet = sourceBook.getSheetAt(0);
            Sheet demoSheet = demoBook.getSheetAt(0);

            for (int index = 1; index <= sourceSheet.getLastRowNum(); index++) {
                Row sourceRow = sourceSheet.getRow(index);
                Row demoRow = demoSheet.getRow(index);
                if (sourceRow == null || demoRow == null) {
                    continue;
                }
                String demoText = rowText(demoRow).toUpperCase(Locale.ROOT);

                for (Field field : FIELDS) {
                    for (String token : tokens(field, text(sourceRow.getCell(field.column())))) {
                        if (demoText.contains(token.toUpperCase(Locale.ROOT))) {
                            leaks.add(new Leak(field.name(), index + 1, mask(token)));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nie udało się porównać plików", e);
        }
        return leaks;
    }

    private static List<String> tokens(Field field, String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return tokens;
        }
        switch (field.name()) {
            case "komentarz — pracodawca" -> {
                for (String section : value.split(";")) {
                    String trimmed = section.strip();
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith("miejsce zatrudnienia:")) {
                        addAll(tokens, trimmed.split("[,:]"));
                    }
                }
            }
            case "telefony" -> addAll(tokens, value.split("[,;]"));
            case "nazwa dłużnika", "ulica", "adres 2" -> addAll(tokens, value.split("[\\s,./-]+"));
            default -> addAll(tokens, new String[]{value});
        }
        return tokens;
    }

    private static void addAll(List<String> tokens, String[] candidates) {
        for (String candidate : candidates) {
            String token = candidate.strip();
            if (token.length() >= MIN_TOKEN_LENGTH
                    && !KEPT_BY_DESIGN.contains(token.toLowerCase(Locale.ROOT))) {
                tokens.add(token);
            }
        }
    }

    private static String rowText(Row row) {
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            if (column == CITY_COLUMN) {
                continue;
            }
            text.append(text(row.getCell(column))).append('|');
        }
        return text.toString();
    }

    /** Wartość pokazywana wyłącznie w postaci zamaskowanej — raport też jest danymi. */
    private static String mask(String value) {
        return value.length() <= 3 ? "***" : value.substring(0, 2) + "*".repeat(value.length() - 2);
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> {
                double number = cell.getNumericCellValue();
                yield number == Math.floor(number)
                        ? String.valueOf((long) number)
                        : String.valueOf(number);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Użycie: LeakCheck <plik źródłowy> <plik demo>");
            System.exit(1);
        }

        List<Leak> leaks = check(new File(args[0]), new File(args[1]));
        if (leaks.isEmpty()) {
            System.out.println("Przecieków: 0 — plik demo nie zawiera wartości z pliku źródłowego.");
            return;
        }

        Map<String, Integer> byField = new LinkedHashMap<>();
        leaks.forEach(leak -> byField.merge(leak.field(), 1, Integer::sum));

        System.out.println("PRZECIEKÓW: " + leaks.size());
        byField.forEach((field, count) -> System.out.println("  " + field + ": " + count));
        leaks.stream().limit(25).forEach(leak -> System.out.println("  - " + leak));
        System.exit(2);
    }
}
