package com.collectorstools.demo;

import com.collectorstools.importer.HeaderMapping;
import com.collectorstools.importer.ImportRow;
import com.collectorstools.importer.XlsxReader;
import com.collectorstools.util.PeselValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy dołączonego pliku demonstracyjnego.
 *
 * <p>Plik powstaje poza budowaniem projektu — anonimizator uruchamia się ręcznie na danych
 * klienta, których w repozytorium nie ma. Bez tego testu nikt nie zauważyłby, że zmiana
 * w imporcie albo w parserze psuje jedyne dane, na jakich aplikację widać w działaniu.
 */
class DemoDataFileTest {

    /** Data sporządzenia eksportu, z którego powstał plik demo. */
    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private static final String RESOURCE = "/demo/zlecenie-demo.xlsx";

    private static File demoFile;

    @BeforeAll
    static void extractResource() throws Exception {
        Path target = Files.createTempFile("zlecenie-demo", ".xlsx");
        try (InputStream in = DemoDataFileTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "Brak zasobu " + RESOURCE);
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        demoFile = target.toFile();
    }

    private List<ImportRow> readDemo() {
        XlsxReader reader = new XlsxReader();
        HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(demoFile));
        assertTrue(mapping.isReady(), "Nagłówki pliku demo nie mapują się na pola importu");
        return reader.readRows(demoFile, mapping, EXPORT_DATE);
    }

    @Test
    @DisplayName("Cały plik demo przechodzi import bez odrzuceń")
    void everyRowIsImportable() {
        List<ImportRow> rows = readDemo();

        assertEquals(171, rows.size());
        List<String> rejected = rows.stream()
                .filter(row -> !row.isImportable())
                .map(row -> row.getSourceRowNumber() + ": " + row.getStatusDescription())
                .toList();
        assertTrue(rejected.isEmpty(), "Odrzucone wiersze: " + rejected);
    }

    @Test
    @DisplayName("Numery PESEL są poprawne, a część osób ma po kilka spraw")
    void keepsValidPeselsAndRepeatedDebtors() {
        List<ImportRow> rows = readDemo();

        List<String> pesels = rows.stream()
                .map(ImportRow::getPesel)
                .filter(pesel -> pesel != null)
                .toList();
        assertTrue(pesels.stream().allMatch(PeselValidator::isValid));

        Set<String> unique = pesels.stream().collect(Collectors.toSet());
        assertTrue(unique.size() < pesels.size(),
                "Plik demo ma stracić powtórzenia PESEL-i, które w portfelu spinały kilka spraw jednej osoby");
    }

    /**
     * Reguła BR-12 — saldo równe sumie składników — jest walidowana przy imporcie,
     * a w portfelu źródłowym zgadzała się co do grosza we wszystkich 171 wierszach.
     */
    @Test
    @DisplayName("Saldo każdego wiersza zgadza się z rozbiciem")
    void balanceMatchesBreakdown() {
        for (ImportRow row : readDemo()) {
            BigDecimal sum = nz(row.getPrincipal()).add(nz(row.getInterest())).add(nz(row.getCosts()));
            if (sum.signum() == 0) {
                continue;
            }
            assertTrue(sum.subtract(row.getBalance()).abs().compareTo(new BigDecimal("0.01")) <= 0,
                    "Wiersz " + row.getSourceRowNumber() + ": " + sum + " zamiast " + row.getBalance());
        }
    }

    @Test
    @DisplayName("Zostają dane, dla których ten plik istnieje")
    void keepsTheDataWorthShowing() {
        List<ImportRow> rows = readDemo();

        long withPromise = rows.stream()
                .filter(row -> row.getComment() != null && row.getComment().hasPromise())
                .count();
        long withPayment = rows.stream()
                .filter(row -> row.getComment() != null && row.getComment().hasLastPayment())
                .count();
        long withPhones = rows.stream().filter(row -> !row.getPhones().isEmpty()).count();
        long visits = rows.stream().filter(ImportRow::isFieldVisit).count();

        assertTrue(withPromise >= 60, "Obietnic spłaty: " + withPromise);
        assertTrue(withPayment >= 150, "Ostatnich wpłat: " + withPayment);
        assertTrue(withPhones >= 140, "Wierszy z telefonem: " + withPhones);
        assertTrue(visits >= 40, "Wizyt terenowych: " + visits);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
