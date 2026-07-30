package com.collectorstools.demo;

import com.collectorstools.exception.TechnicalException;
import com.collectorstools.importer.HeaderMapping;
import com.collectorstools.importer.ImportRow;
import com.collectorstools.importer.ImportSummary;
import com.collectorstools.importer.XlsxReader;
import com.collectorstools.service.CaseImportService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

/**
 * Wgrywa dołączone dane demonstracyjne do bazy.
 *
 * <p>Dane przechodzą <b>tą samą ścieżką co plik od wierzyciela</b> — przez czytnik XLSX,
 * automatyczne dopasowanie nagłówków i {@link CaseImportService}. Osobna, „szybsza" droga
 * zapisu wprost do bazy dawałaby stan, którego zwykły import nigdy by nie wyprodukował,
 * i pierwszy problem z importem wychodziłby dopiero u użytkownika.
 *
 * <p>O tym, skąd wzięły się te dane i co w nich zostało zmienione, mówi
 * {@code docs/demo-data.md}.
 */
public class DemoDataLoader {

    private static final String RESOURCE = "/demo/zlecenie-demo.xlsx";

    /**
     * Data sporządzenia eksportu, z którego powstał plik demo. Ustala rok w terminach
     * zapisanych bez roku, więc musi zostać przy pliku — dzisiejsza data przesunęłaby
     * wszystkie deklaracje o kilka lat.
     */
    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private final CaseImportService importService;
    private final XlsxReader reader = new XlsxReader();

    public DemoDataLoader() {
        this.importService = new CaseImportService();
    }

    public DemoDataLoader(CaseImportService importService) {
        this.importService = importService;
    }

    /**
     * Wczytuje wszystkie sprawy z pliku demonstracyjnego.
     *
     * <p>Wgranie po raz drugi nie zdublikuje dłużników — import rozpoznaje ich po numerze
     * PESEL i dopina kolejne sprawy do istniejących osób. Sprawy powstaną jednak nowe,
     * bo numer sprawy nie jest w bazie unikalny.
     */
    public ImportSummary load() {
        File file = extractResource();
        try {
            HeaderMapping mapping = HeaderMapping.autoDetect(reader.readHeaders(file));
            if (!mapping.isReady()) {
                throw new TechnicalException(
                        "Dołączony plik z danymi demonstracyjnymi ma nieoczekiwane kolumny.",
                        "Nagłówki pliku " + RESOURCE + " nie mapują się na pola importu.", null);
            }

            List<ImportRow> rows = reader.readRows(file, mapping, EXPORT_DATE);
            importService.markExistingDebtors(rows);
            return importService.importRows(rows, "dane demonstracyjne");
        } finally {
            // Kopia robocza nie jest już potrzebna; plik źródłowy zostaje w zasobach
            file.delete();
        }
    }

    /**
     * Zasób trafia do pliku tymczasowego, bo czytnik XLSX pracuje na plikach —
     * w spakowanym JAR-ze zasób nie ma ścieżki w systemie plików.
     */
    private File extractResource() {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new TechnicalException(
                        "Aplikacja nie zawiera danych demonstracyjnych.",
                        "Brak zasobu " + RESOURCE, null);
            }
            Path copy = Files.createTempFile("collectorstools-demo", ".xlsx");
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            copy.toFile().deleteOnExit();
            return copy.toFile();
        } catch (IOException e) {
            throw new TechnicalException(
                    "Nie udało się przygotować danych demonstracyjnych.", e.getMessage(), e);
        }
    }
}
