package com.collectorstools.importer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class XlsxReaderTest {

    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private static final String COMMENT = "Portfel: DETAL ; kategoria sprawy: EGZEKUCJE 2 KAT C; "
            + "zezwolenie na zawarcie ugody: TAK; status kredytu: PO OKRESIE WYPOWIEDZENIA; "
            + "Miejsce zatrudnienia: CENTRUM KULTURY, adres zatrudnienia:LUBLIN,tel. do pracy:815360322;"
            + "data i kwota ostatniej wpłaty: 2016-06-16 81,89; Data wszczęcia egzekucji: 2015-02-17; "
            + "treść notatki: ; treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 50 PLN 31,12";

    @TempDir
    Path directory;

    private XlsxReader reader;

    @BeforeEach
    void setUp() {
        reader = new XlsxReader();
    }

    private List<ImportRow> read(Object[]... rows) throws Exception {
        File file = TestWorkbook.write(directory, "import.xlsx", List.of(rows));
        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
        return reader.readRows(file, mapping, EXPORT_DATE);
    }

    @Test
    @DisplayName("Odczytuje nagłówki pliku")
    void readsHeaders() throws Exception {
        File file = TestWorkbook.write(directory, "import.xlsx", List.of());

        assertEquals(TestWorkbook.HEADERS, reader.readHeaders(file));
    }

    @Test
    @DisplayName("Odczytuje kompletny wiersz razem z danymi z komentarza")
    void readsCompleteRow() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "25576", "tak", "URSZULA PIŁAT", "83120102021",
                377.69, 196.40, 181.29, 0.0, "ALIOR", "817442336,662701256", COMMENT));

        assertEquals(1, rows.size());
        ImportRow row = rows.get(0);

        assertTrue(row.isImportable());
        assertEquals("URSZULA", row.getFirstName());
        assertEquals("PIŁAT", row.getLastName());
        assertEquals("83120102021", row.getPesel());
        assertEquals("Cyrulicza 2/9", row.getStreetAddress());
        assertEquals("25576", row.getCaseNumber());
        assertEquals(0, new BigDecimal("377.69").compareTo(row.getBalance()));
        assertTrue(row.isFieldVisit());
        assertEquals(LocalDate.of(2016, 11, 15), row.getServiceStartDate(),
                "Data zapisana jako tekst z częścią czasową");

        ParsedComment comment = row.getComment();
        assertEquals("EGZEKUCJE 2 KAT C", comment.caseCategory());
        assertEquals(Boolean.TRUE, comment.settlementAllowed());
        assertTrue(comment.hasLastPayment());
        assertTrue(comment.hasPromise());
    }

    @Test
    @DisplayName("Rozdziela numery telefonu i pomija duplikaty")
    void splitsPhoneNumbers() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "1", "nie", "JAN KOWALSKI", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", "817442336, 662701256 ,817442336", null));

        assertEquals(List.of("817442336", "662701256"), rows.get(0).getPhones());
    }

    @Test
    @DisplayName("Nazwisko dwuczłonowe nie jest ucinane")
    void keepsCompoundSurname() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "1", "nie", "ANNA NOWAK KOWALSKA", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", null, null));

        assertEquals("ANNA", rows.get(0).getFirstName());
        assertEquals("NOWAK KOWALSKA", rows.get(0).getLastName());
    }

    @Test
    @DisplayName("Błędny PESEL odrzuca wiersz")
    void rejectsInvalidPesel() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "1", "nie", "JAN KOWALSKI", "12345678901",
                100.0, 100.0, 0.0, 0.0, "ALIOR", null, null));

        ImportRow row = rows.get(0);
        assertFalse(row.isImportable());
        assertTrue(row.getErrors().get(0).contains("PESEL"));
    }

    @Test
    @DisplayName("Brak PESEL nie odrzuca wiersza, ale jest zgłaszany")
    void allowsMissingPesel() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "1", "nie", "JAN KOWALSKI", null,
                100.0, 100.0, 0.0, 0.0, "ALIOR", null, null));

        ImportRow row = rows.get(0);
        assertTrue(row.isImportable(), "7 spraw w rzeczywistym portfelu nie miało numeru PESEL");
        assertNull(row.getPesel());
        assertTrue(row.getWarnings().stream().anyMatch(w -> w.contains("PESEL")));
    }

    @Test
    @DisplayName("Rozjazd rozbicia salda odrzuca wiersz")
    void rejectsInconsistentBreakdown() throws Exception {
        List<ImportRow> rows = read(TestWorkbook.row(
                "1", "nie", "JAN KOWALSKI", "83120102021",
                377.69, 100.0, 100.0, 0.0, "ALIOR", null, null));

        ImportRow row = rows.get(0);
        assertFalse(row.isImportable());
        assertTrue(row.getErrors().get(0).contains("Rozbicie salda"));
    }

    @Test
    @DisplayName("Brak wymaganych danych odrzuca wiersz z opisem przyczyny")
    void rejectsRowMissingRequiredData() throws Exception {
        Object[] noCreditor = TestWorkbook.row("1", "nie", "JAN KOWALSKI", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", null, null);
        noCreditor[17] = null;

        List<ImportRow> rows = read(noCreditor);

        assertFalse(rows.get(0).isImportable());
        assertTrue(rows.get(0).getErrors().get(0).contains("wierzyciela"));
    }

    @Test
    @DisplayName("Puste wiersze są pomijane")
    void skipsBlankRows() throws Exception {
        List<ImportRow> rows = read(
                TestWorkbook.row("1", "nie", "JAN KOWALSKI", "83120102021",
                        100.0, 100.0, 0.0, 0.0, "ALIOR", null, null),
                new Object[TestWorkbook.HEADERS.size()]);

        assertEquals(1, rows.size());
    }

    /**
     * Kopia dołączonego pliku demonstracyjnego — pliku „z życia", a nie świeżo napisanego
     * przez POI w tym samym procesie. Ta różnica jest istotna: plik dopiero co zapisany
     * przez tę samą wersję biblioteki przepisuje się bajt w bajt tak samo, więc na nim
     * naruszenie pliku źródłowego byłoby niewidoczne.
     */
    private File realWorldFile() throws Exception {
        File file = directory.resolve("zlecenie.xlsx").toFile();
        try (var in = getClass().getResourceAsStream("/demo/zlecenie-demo.xlsx")) {
            assertNotNull(in, "Brak zasobu /demo/zlecenie-demo.xlsx");
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /**
     * Odczyt nie może naruszyć pliku źródłowego.
     *
     * <p>{@code WorkbookFactory.create(File)} otwiera plik w trybie do zapisu, a POI zapisuje
     * go z powrotem przy zamykaniu. Efekt: podgląd importu przepisywał plik zlecenia
     * wierzyciela, zanim użytkownik cokolwiek zatwierdził — narzędzie importujące nie ma
     * prawa dotykać cudzych danych.
     */
    @Test
    @DisplayName("Odczyt nie zmienia pliku źródłowego")
    void doesNotModifySourceFile() throws Exception {
        File file = realWorldFile();
        byte[] before = Files.readAllBytes(file.toPath());

        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
        assertEquals(171, reader.readRows(file, mapping, EXPORT_DATE).size());

        assertArrayEquals(before, Files.readAllBytes(file.toPath()),
                "Plik źródłowy został zmieniony przez sam odczyt");
    }

    /**
     * Plik bez prawa zapisu ma się dać zaimportować.
     *
     * <p>Zlecenia przychodzą na dyskach sieciowych i w katalogach współdzielonych, gdzie
     * prawo zapisu bywa wyłączone. Otwarcie w trybie do zapisu kończyło się tam
     * „Permission denied" — komunikatem, który sugeruje uszkodzony plik, a nie brak
     * uprawnienia, którego odczyt i tak nie potrzebuje.
     */
    @Test
    @DisplayName("Plik tylko do odczytu daje się zaimportować")
    void importsFileWithoutWriteAccess() throws Exception {
        File file = realWorldFile();
        assumeTrue(file.setWritable(false) && !file.canWrite(),
                "System nie egzekwuje prawa zapisu — test nie miałby czego sprawdzić");

        try {
            HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
            assertEquals(171, reader.readRows(file, mapping, EXPORT_DATE).size());
        } finally {
            file.setWritable(true);
        }
    }

    @Test
    @DisplayName("Numer wiersza wskazuje pozycję w pliku widzianą w Excelu")
    void reportsSourceRowNumber() throws Exception {
        List<ImportRow> rows = read(
                TestWorkbook.row("1", "nie", "JAN KOWALSKI", "83120102021",
                        100.0, 100.0, 0.0, 0.0, "ALIOR", null, null),
                TestWorkbook.row("2", "nie", "ANNA NOWAK", "58041612063",
                        200.0, 200.0, 0.0, 0.0, "BPH", null, null));

        assertEquals(2, rows.get(0).getSourceRowNumber());
        assertEquals(3, rows.get(1).getSourceRowNumber());
    }
}
