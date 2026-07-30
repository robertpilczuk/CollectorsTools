package com.collectorstools.demo;

import com.collectorstools.importer.HeaderMapping;
import com.collectorstools.importer.ImportRow;
import com.collectorstools.importer.TestWorkbook;
import com.collectorstools.importer.XlsxReader;
import com.collectorstools.util.PeselValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy anonimizacji pliku wierzyciela.
 *
 * <p>Sprawdzają obie strony umowy: że dane wskazujące na osobę znikają i że wszystko,
 * co czyni te dane wartościowym materiałem demonstracyjnym — kwoty, terminy, kategorie,
 * relacja jeden dłużnik : wiele spraw — zostaje. Błąd w pierwszą stronę wynosi dane
 * osobowe do publicznego repozytorium; błąd w drugą daje 171 wierszy bez treści.
 */
class DataAnonymizerTest {

    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private static final String COMMENT = "Portfel: DETAL; kategoria sprawy: EGZEKUCJE 2 KAT C; "
            + "zezwolenie na zawarcie ugody: TAK; status kredytu: PO OKRESIE WYPOWIEDZENIA; "
            + "Miejsce zatrudnienia: SZKOŁA PODSTAWOWA NR 3, adres zatrudnienia: LUBLIN UL. DŁUGA 5,"
            + "tel. do pracy: 815360322; data i kwota ostatniej wpłaty: 2016-06-16 81,89; "
            + "Data wszczęcia egzekucji: 2015-02-17; "
            + "treść ostatniego kontaktu z klientem: ROZMOWA Z SIOSTRĄ KLIENTKI, "
            + "OBIETNICA SPŁATY 50 PLN 31,12";

    @TempDir
    Path directory;

    private DataAnonymizer anonymizer;
    private XlsxReader reader;

    @BeforeEach
    void setUp() {
        anonymizer = new DataAnonymizer();
        reader = new XlsxReader();
    }

    /** Anonimizuje plik zbudowany z podanych wierszy i odczytuje wynik importerem. */
    private List<ImportRow> anonymizeAndRead(AnonymizationReport[] out, Object[]... rows) throws Exception {
        File source = TestWorkbook.write(directory, "zrodlo.xlsx", List.of(rows));
        File target = directory.resolve("demo.xlsx").toFile();

        AnonymizationReport report = anonymizer.anonymize(source, target, EXPORT_DATE);
        if (out != null) {
            out[0] = report;
        }
        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(target));
        return reader.readRows(target, mapping, EXPORT_DATE);
    }

    @Test
    @DisplayName("Wymienia dane wskazujące na osobę, zostawia kwoty i słowniki wierzyciela")
    void replacesIdentifyingDataAndKeepsAmounts() throws Exception {
        List<ImportRow> rows = anonymizeAndRead(null, TestWorkbook.row(
                "25576", "tak", "URSZULA PIŁAT", "83120102021",
                377.69, 196.40, 181.29, 0.0, "ALIOR", "817442336,662701256", COMMENT));

        ImportRow row = rows.get(0);
        assertNotEquals("URSZULA", row.getFirstName());
        assertNotEquals("PIŁAT", row.getLastName());
        assertNotEquals("83120102021", row.getPesel());
        assertNotEquals("Cyrulicza", row.getStreet());
        assertNotEquals("25576", row.getCaseNumber());
        assertFalse(row.getPhones().contains("817442336"));

        assertEquals(0, new BigDecimal("377.69").compareTo(row.getBalance()));
        assertEquals(0, new BigDecimal("196.40").compareTo(row.getPrincipal()));
        assertEquals("ALIOR", row.getCreditor());
        assertEquals("ALIOR_ZLEC_024", row.getPortfolio());
        assertEquals("KREDYT GOTÓWKOWY WALMED", row.getProduct());
        assertEquals("LUBLIN", row.getCity());
        assertEquals(LocalDate.of(2016, 11, 15), row.getServiceStartDate());
        assertTrue(row.isFieldVisit());
    }

    /**
     * Numer zastępczy musi przejść walidację, inaczej import pliku demo pokazywałby
     * same odrzucenia zamiast danych.
     */
    @Test
    @DisplayName("Wystawia poprawne numery PESEL")
    void generatedPeselIsValid() throws Exception {
        List<ImportRow> rows = anonymizeAndRead(null,
                TestWorkbook.row("1", "nie", "JAN NOWAK", "83120102021",
                        100.0, 100.0, 0.0, 0.0, "ALIOR", "", COMMENT),
                TestWorkbook.row("2", "nie", "EWA MAJ", "70051512347",
                        200.0, 200.0, 0.0, 0.0, "BPH", "", COMMENT));

        assertTrue(rows.stream().allMatch(row -> PeselValidator.isValid(row.getPesel())));
        assertTrue(rows.stream().allMatch(ImportRow::isImportable));
    }

    /**
     * 16 osób z portfela miało po kilka spraw. Gdyby ten sam PESEL dostawał za każdym
     * razem inną tożsamość, dane demo przestałyby pokazywać relację jeden dłużnik : wiele spraw.
     */
    @Test
    @DisplayName("Ten sam PESEL źródłowy daje wszędzie tę samą osobę zastępczą")
    void sameSourcePeselGivesSamePerson() throws Exception {
        AnonymizationReport[] report = new AnonymizationReport[1];
        List<ImportRow> rows = anonymizeAndRead(report,
                TestWorkbook.row("1", "nie", "JAN NOWAK", "83120102021",
                        100.0, 100.0, 0.0, 0.0, "ALIOR", "", COMMENT),
                TestWorkbook.row("2", "nie", "JAN NOWAK", "83120102021",
                        200.0, 200.0, 0.0, 0.0, "ALIOR", "", COMMENT));

        assertEquals(rows.get(0).getPesel(), rows.get(1).getPesel());
        assertEquals(rows.get(0).getLastName(), rows.get(1).getLastName());
        assertNotEquals(rows.get(0).getCaseNumber(), rows.get(1).getCaseNumber());
        assertEquals(1, report[0].getPeople());
    }

    @Test
    @DisplayName("Wycina swobodną notatkę, zachowując odczytaną obietnicę spłaty")
    void dropsFreeTextButKeepsPromise() throws Exception {
        AnonymizationReport[] report = new AnonymizationReport[1];
        List<ImportRow> rows = anonymizeAndRead(report, TestWorkbook.row(
                "25576", "nie", "URSZULA PIŁAT", "83120102021",
                377.69, 377.69, 0.0, 0.0, "ALIOR", "", COMMENT));

        var comment = rows.get(0).getComment();
        assertFalse(comment.lastContact().contains("SIOSTRĄ"), comment.lastContact());
        assertTrue(comment.hasPromise());
        assertEquals(0, new BigDecimal("50").compareTo(comment.promise().amount()));
        assertEquals(LocalDate.of(2016, 12, 31), comment.promise().dueDate());
        assertEquals(1, report[0].getPromisesKept());

        // Sekcje słownikowe i kwotowe zostają nietknięte
        assertEquals("EGZEKUCJE 2 KAT C", comment.caseCategory());
        assertEquals("PO OKRESIE WYPOWIEDZENIA", comment.creditStatus());
        assertEquals(Boolean.TRUE, comment.settlementAllowed());
        assertEquals(LocalDate.of(2016, 6, 16), comment.lastPaymentDate());
        assertEquals(0, new BigDecimal("81.89").compareTo(comment.lastPaymentAmount()));
        assertEquals(LocalDate.of(2015, 2, 17), comment.enforcementStartDate());
    }

    @Test
    @DisplayName("Wymienia pracodawcę razem z jego adresem i telefonem")
    void replacesEmployerData() throws Exception {
        List<ImportRow> rows = anonymizeAndRead(null, TestWorkbook.row(
                "1", "nie", "JAN NOWAK", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", "", COMMENT));

        var comment = rows.get(0).getComment();
        assertNotEquals("SZKOŁA PODSTAWOWA NR 3", comment.employerName());
        assertNotEquals("LUBLIN UL. DŁUGA 5", comment.employerAddress());
        assertNotEquals("815360322", comment.employerPhone());
        assertTrue(comment.hasEmployer());
    }

    /**
     * Sekcja bez pracodawcy też jest budowana od nowa. Obok „NIE DOTYCZY" w polu nazwy
     * potrafi stać prawdziwy adres — przepuszczenie takiej sekcji w całości wynosiłoby go
     * do pliku demo.
     */
    @Test
    @DisplayName("Buduje sekcję zatrudnienia od nowa także wtedy, gdy pracodawcy nie podano")
    void rebuildsEmploymentSectionEvenWithoutEmployer() throws Exception {
        String comment = "Portfel: DETAL; Miejsce zatrudnienia: NIE DOTYCZY, "
                + "adres zatrudnienia: LUBLIN UL. DŁUGA 5, tel. do pracy: 815360322; "
                + "status kredytu: PO OKRESIE WYPOWIEDZENIA";

        List<ImportRow> rows = anonymizeAndRead(null, TestWorkbook.row(
                "1", "nie", "JAN NOWAK", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", "", comment));

        var parsed = rows.get(0).getComment();
        assertNull(parsed.employerAddress());
        assertNull(parsed.employerPhone());
        assertEquals("PO OKRESIE WYPOWIEDZENIA", parsed.creditStatus());
    }

    /**
     * Reguła bezpieczeństwa: czego narzędzie nie rozpoznało, tego nie przepuszcza.
     * Kolumna spoza mapowania i sekcja komentarza spoza listy znikają, a raport je zgłasza.
     */
    @Test
    @DisplayName("Czyści nierozpoznane kolumny i sekcje, zgłaszając je w raporcie")
    void blanksWhatItDoesNotRecognise() throws Exception {
        Object[] row = TestWorkbook.row("1", "nie", "JAN NOWAK", "83120102021",
                100.0, 100.0, 0.0, 0.0, "ALIOR", "",
                COMMENT + "; nowa sekcja wierzyciela: DANE OSOBOWE KLIENTA");
        row[10] = "Adres 3: dane wrażliwe";      // kolumna spoza mapowania
        row[25] = "notatka z wizyty: kto otworzył drzwi";

        AnonymizationReport[] report = new AnonymizationReport[1];
        List<ImportRow> rows = anonymizeAndRead(report, row);

        assertNull(rows.get(0).getVisitNote());
        assertTrue(report[0].getBlankedColumns().contains("Adres 3"),
                report[0].getBlankedColumns().toString());
        assertTrue(report[0].getBlankedColumns().contains("notatka z wizyty"),
                report[0].getBlankedColumns().toString());
        assertTrue(report[0].getDroppedSections().contains("nowa sekcja wierzyciela"),
                report[0].getDroppedSections().toString());
    }

    /**
     * Wynik ma przechodzić tę samą ścieżkę importu co plik wierzyciela — inaczej
     * dane demo nie pokazywałyby działania importu, tylko własny, osobny format.
     */
    @Test
    @DisplayName("Plik wynikowy nadaje się do importu bez odrzuceń")
    void producedFileImportsWithoutRejections() throws Exception {
        AnonymizationReport[] report = new AnonymizationReport[1];
        List<ImportRow> rows = anonymizeAndRead(report,
                TestWorkbook.row("1", "tak", "JAN NOWAK", "83120102021",
                        377.69, 196.40, 181.29, 0.0, "ALIOR", "817442336", COMMENT),
                TestWorkbook.row("2", "nie", "EWA MAJ", "70051512347",
                        25.47, 25.47, 0.0, 0.0, "BPH", "662701256,817442336", COMMENT));

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(ImportRow::isImportable),
                rows.stream().map(ImportRow::getStatusDescription).toList().toString());
        assertEquals(2, report[0].getRows());
        assertEquals(2, report[0].getCommentsRewritten());
    }
}
