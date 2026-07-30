package com.collectorstools.demo;

import com.collectorstools.importer.CaseCommentParser;
import com.collectorstools.importer.HeaderMapping;
import com.collectorstools.importer.ImportField;
import com.collectorstools.importer.ParsedComment;
import com.collectorstools.importer.PromiseOfPayment;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zamienia plik z danymi rzeczywistych dłużników na plik demonstracyjny.
 *
 * <p>Plik wejściowy zawiera PESEL-e, nazwiska, adresy i numery telefonów realnych osób
 * i nie może trafić do repozytorium. Wynik zachowuje wszystko, co czyni te dane ciekawymi
 * — kwoty, terminy, kategorie spraw, rozkład portfeli, relację jeden dłużnik : wiele spraw —
 * i nie zawiera niczego, co wskazywałoby na osobę.
 *
 * <p><b>Zasada bezpieczeństwa: nierozpoznane znaczy usunięte.</b> Kolumna, której narzędzie
 * nie umie zakwalifikować, i sekcja komentarza spoza znanej listy są czyszczone, a nie
 * przepuszczane. Odwrotna domyślność — „przepuść, czego nie znam" — oznaczałaby, że każda
 * nowa kolumna w eksporcie wierzyciela po cichu wynosi dane osobowe do publicznego repo.
 *
 * <p>Uruchomienie:
 * <pre>
 *   java -cp target/classes:… com.collectorstools.demo.DataAnonymizer \
 *        "data/Zlecenie - Lubelskie 08.02.2017.xlsx" \
 *        src/main/resources/demo/zlecenie-demo.xlsx 2017-02-08
 * </pre>
 */
public class DataAnonymizer {

    private static final DateTimeFormatter PROMISE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Sekcje komentarza przepisywane bez zmian. Niosą kwoty, daty i słownikowe etykiety
     * wierzyciela — nic, co wskazywałoby na osobę.
     */
    private static final List<String> SAFE_SECTIONS = List.of(
            "portfel", "kategoria sprawy", "status kredytu", "kwota kredytu",
            "zezwolenie na zawarcie ugody", "data i kwota ostatniej wpłaty",
            "data wszczęcia egzekucji");

    /** Sekcja zatrudnienia — nazwa, adres i telefon pracodawcy wymieniane na zastępcze. */
    private static final String EMPLOYMENT_SECTION = "miejsce zatrudnienia";

    /** Swobodne notatki windykatora. Zastępowane, bo mogą zawierać dowolną treść. */
    private static final List<String> FREE_TEXT_SECTIONS =
            List.of("treść ostatniego kontaktu z klientem", "treść notatki");

    private static final List<String> GENERIC_NOTES = List.of(
            "KONTAKT TELEFONICZNY, KLIENT DEKLARUJE TRUDNĄ SYTUACJĘ FINANSOWĄ.",
            "BRAK KONTAKTU, TELEFON NIE ODPOWIADA.",
            "KLIENT PROSI O KONTAKT W PRZYSZŁYM TYGODNIU.",
            "ROZMOWA PRZEPROWADZONA, USTALENIA BEZ KONKRETNEJ DATY.",
            "KLIENT ODMAWIA ROZMOWY O ZADŁUŻENIU.");

    private final FakeIdentities identities = new FakeIdentities();
    private final CaseCommentParser commentParser = new CaseCommentParser();

    /**
     * @param referenceDate data sporządzenia eksportu — potrzebna, żeby odczytać terminy
     *                      zapisane bez roku przed przepisaniem ich w jednoznacznej postaci
     */
    public AnonymizationReport anonymize(File source, File target, LocalDate referenceDate) {
        AnonymizationReport report = new AnonymizationReport();

        try (Workbook sourceBook = WorkbookFactory.create(source);
             XSSFWorkbook targetBook = new XSSFWorkbook()) {

            Sheet sourceSheet = sourceBook.getSheetAt(0);
            Sheet targetSheet = targetBook.createSheet("Arkusz1");

            int headerIndex = sourceSheet.getFirstRowNum();
            List<String> headers = readHeaders(sourceSheet.getRow(headerIndex));
            HeaderMapping mapping = HeaderMapping.autoDetect(headers);
            Map<Integer, ImportField> byColumn = invert(mapping);

            Row targetHeader = targetSheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                targetHeader.createCell(column).setCellValue(headers.get(column));
            }

            int written = 0;
            for (int index = headerIndex + 1; index <= sourceSheet.getLastRowNum(); index++) {
                Row sourceRow = sourceSheet.getRow(index);
                if (sourceRow == null) {
                    continue;
                }
                anonymizeRow(sourceRow, targetSheet.createRow(++written),
                        headers, byColumn, referenceDate, report);
                report.countRow();
            }

            report.setPeople(identities.peopleCount());

            try (FileOutputStream out = new FileOutputStream(target)) {
                targetBook.write(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nie udało się przetworzyć pliku " + source, e);
        }
        return report;
    }

    private void anonymizeRow(Row sourceRow, Row targetRow, List<String> headers,
                              Map<Integer, ImportField> byColumn, LocalDate referenceDate,
                              AnonymizationReport report) {
        // Klucz tożsamości: PESEL, bo to on spina kilka spraw jednej osoby.
        // Bez numeru zostaje nazwisko — gorsze, ale w tym pliku dotyczy 7 wierszy.
        String pesel = cellText(sourceRow, byColumn, ImportField.PESEL);
        String name = cellText(sourceRow, byColumn, ImportField.DEBTOR_NAME);
        FakeIdentities.FakePerson person = identities.personFor(
                pesel != null && !pesel.isBlank() ? pesel : String.valueOf(name), name);

        for (int column = 0; column < headers.size(); column++) {
            Cell sourceCell = sourceRow.getCell(column);
            ImportField field = byColumn.get(column);

            if (field == null) {
                // Kolumna nierozpoznana — zostaje pusta, a jej nagłówek trafia do raportu
                if (!text(sourceCell).isEmpty()) {
                    report.blankedColumn(headers.get(column));
                }
                continue;
            }

            String original = text(sourceCell);
            switch (field) {
                // Wartości nieosobowe: kwoty, słowniki wierzyciela, daty obsługi
                case BALANCE, PRINCIPAL, INTEREST, COSTS, CREDITOR, ORIGINAL_CREDITOR,
                     PORTFOLIO, PRODUCT, SERVICE_START_DATE, FIELD_VISIT, CITY, COURT ->
                        copy(sourceCell, targetRow, column);

                case DEBTOR_NAME -> set(targetRow, column, person.fullName());
                case PESEL -> set(targetRow, column, original.isEmpty() ? "" : person.pesel());
                case PHONES -> set(targetRow, column, anonymizePhones(original));
                case STREET -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.streetFor(original));
                case HOUSE_NUMBER -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.houseNumberFor(original));
                case FLAT_NUMBER -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.flatNumberFor(original));
                case POSTAL_CODE -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.postalCodeFor(original));
                case SECONDARY_ADDRESS -> set(targetRow, column,
                        original.isEmpty() ? "" : anonymizeAddress(original));
                case CASE_NUMBER -> set(targetRow, column, identities.caseNumberFor(original));
                case BANK_ACCOUNT -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.accountFor(original));
                case COURT_SIGNATURE -> set(targetRow, column,
                        original.isEmpty() ? "" : identities.courtSignatureFor(original));

                case COMMENT -> {
                    if (!original.isEmpty()) {
                        set(targetRow, column, anonymizeComment(original, referenceDate, report));
                        report.countCommentRewritten();
                    }
                }

                // Notatka z wizyty to swobodny tekst bez struktury — nie da się jej
                // bezpiecznie przepisać, więc nie wchodzi do danych demo
                case VISIT_NOTE -> {
                    if (!original.isEmpty()) {
                        report.blankedColumn(headers.get(column));
                    }
                }
            }
        }
    }

    private String anonymizePhones(String original) {
        if (original.isEmpty()) {
            return "";
        }
        return Arrays.stream(original.split("[,;]"))
                .map(String::strip)
                .filter(phone -> !phone.isEmpty())
                .map(identities::phoneFor)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private String anonymizeAddress(String original) {
        return "%s %s, %s %s".formatted(
                identities.streetFor(original),
                identities.houseNumberFor(original),
                identities.postalCodeFor(null),
                FakeIdentities.FICTIONAL_TOWN);
    }

    /**
     * Przepisuje komentarz sekcja po sekcji.
     *
     * <p>Struktura pola jest zachowana, bo to na niej stoi parser — plik demo ma
     * przechodzić przez tę samą ścieżkę importu co plik wierzyciela, inaczej nie
     * pokazywałby, jak działa import.
     */
    String anonymizeComment(String comment, LocalDate referenceDate, AnonymizationReport report) {
        ParsedComment parsed = commentParser.parse(comment, referenceDate);
        List<String> rebuilt = new ArrayList<>();

        for (String section : comment.split(";")) {
            String trimmed = section.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String label = labelOf(trimmed);

            if (SAFE_SECTIONS.contains(label)) {
                rebuilt.add(trimmed);
            } else if (label.startsWith(EMPLOYMENT_SECTION)) {
                rebuilt.add(employmentSection(trimmed));
            } else if (FREE_TEXT_SECTIONS.contains(label)) {
                rebuilt.add(replaceFreeText(label, trimmed, parsed, report));
            } else {
                report.droppedSection(label.isEmpty() ? shorten(trimmed) : label);
            }
        }
        return String.join("; ", rebuilt);
    }

    /**
     * Nazwa, adres i telefon pracodawcy wymieniane razem — sekcja ma stały układ
     * „Miejsce zatrudnienia: X, adres zatrudnienia: Y, tel. do pracy: Z".
     *
     * <p>Sekcja jest budowana od nowa również wtedy, gdy pracodawcy nie podano. Wcześniejsza
     * wersja przepuszczała taką sekcję w całości, ufając, że skoro nie ma pracodawcy, to nie
     * ma i danych — a w pliku wierzyciela obok „NIE DOTYCZY" w polu nazwy stały wypełniacze
     * własnej roboty („brak danych", literówki) i równie dobrze mógł stać prawdziwy adres.
     */
    private String employmentSection(String section) {
        if (isPlaceholder(valueOf(section, EMPLOYMENT_SECTION))) {
            return "Miejsce zatrudnienia: NIE DOTYCZY, adres zatrudnienia: NIE DOTYCZY, "
                    + "tel. do pracy: NIE DOTYCZY";
        }
        return "Miejsce zatrudnienia: %s, adres zatrudnienia: %s, tel. do pracy: %s".formatted(
                identities.employerFor(section),
                identities.employerAddressFor(section),
                identities.phoneFor(section));
    }

    /** Wartość pierwszego pola sekcji — tekst między dwukropkiem a przecinkiem. */
    private static String valueOf(String section, String label) {
        int start = section.toLowerCase(Locale.ROOT).indexOf(label);
        int colon = section.indexOf(':', start < 0 ? 0 : start);
        if (colon < 0) {
            return "";
        }
        int comma = section.indexOf(',', colon);
        return (comma < 0 ? section.substring(colon + 1) : section.substring(colon + 1, comma)).strip();
    }

    /**
     * Czy wartość oznacza „nie podano". Poza „NIE DOTYCZY" w pliku występują wypełniacze
     * wpisywane ręcznie, łącznie z literówkami — stąd dopasowanie po przedrostku.
     */
    private static boolean isPlaceholder(String value) {
        String normalised = value.toLowerCase(Locale.ROOT).strip();
        return normalised.isEmpty()
                || normalised.startsWith("nie d")
                || normalised.startsWith("brak")
                || normalised.equals("-");
    }

    /**
     * Notatka swobodna zastąpiona tekstem zastępczym. Odczytana wcześniej obietnica spłaty
     * wraca w postaci jednoznacznej — bez niej plik demo straciłby 77 deklaracji, czyli
     * to, co w tych danych najlepiej pokazuje sens aplikacji.
     */
    private String replaceFreeText(String label, String section, ParsedComment parsed,
                                   AnonymizationReport report) {
        report.countContactNoteReplaced();
        String replacement = GENERIC_NOTES.get(Math.abs(section.hashCode()) % GENERIC_NOTES.size());

        PromiseOfPayment promise = parsed.promise();
        boolean contactSection = label.startsWith("treść ostatniego kontaktu");
        if (contactSection && promise != null && promise.amount() != null && promise.dueDate() != null) {
            replacement += " OBIETNICA SPŁATY %s PLN %s".formatted(
                    promise.amount().toPlainString().replace('.', ','),
                    promise.dueDate().format(PROMISE_DATE));
            report.countPromiseKept();
        }
        return label + ": " + replacement;
    }

    /** Etykieta sekcji, czyli tekst przed pierwszym dwukropkiem, w postaci porównywalnej. */
    private static String labelOf(String section) {
        int colon = section.indexOf(':');
        return colon < 0 ? "" : section.substring(0, colon).strip().toLowerCase(Locale.ROOT);
    }

    private static String shorten(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 40 ? single.substring(0, 40) + "…" : single;
    }

    private static List<String> readHeaders(Row header) {
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < header.getLastCellNum(); column++) {
            headers.add(text(header.getCell(column)));
        }
        return headers;
    }

    private static Map<Integer, ImportField> invert(HeaderMapping mapping) {
        Map<Integer, ImportField> byColumn = new HashMap<>();
        for (ImportField field : ImportField.values()) {
            Integer column = mapping.columnOf(field);
            if (column != null) {
                byColumn.put(column, field);
            }
        }
        return byColumn;
    }

    private String cellText(Row row, Map<Integer, ImportField> byColumn, ImportField field) {
        return byColumn.entrySet().stream()
                .filter(entry -> entry.getValue() == field)
                .findFirst()
                .map(entry -> text(row.getCell(entry.getKey())))
                .orElse(null);
    }

    /** Kopiuje wartość bez zmiany typu — kwoty mają zostać liczbami, nie tekstem. */
    private static void copy(Cell source, Row targetRow, int column) {
        if (source == null) {
            return;
        }
        switch (source.getCellType()) {
            case NUMERIC -> targetRow.createCell(column).setCellValue(source.getNumericCellValue());
            case BOOLEAN -> targetRow.createCell(column).setCellValue(source.getBooleanCellValue());
            case STRING -> targetRow.createCell(column).setCellValue(source.getStringCellValue());
            default -> { /* formuły i puste komórki nie wnoszą danych do pliku demo */ }
        }
    }

    private static void set(Row targetRow, int column, String value) {
        if (value != null && !value.isEmpty()) {
            targetRow.createCell(column).setCellValue(value);
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
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
            System.err.println("Użycie: DataAnonymizer <plik źródłowy> <plik wynikowy> [data eksportu]");
            System.exit(1);
        }
        LocalDate referenceDate = args.length > 2 ? LocalDate.parse(args[2]) : LocalDate.now();
        AnonymizationReport report =
                new DataAnonymizer().anonymize(new File(args[0]), new File(args[1]), referenceDate);
        System.out.println(report.describe());
    }
}
