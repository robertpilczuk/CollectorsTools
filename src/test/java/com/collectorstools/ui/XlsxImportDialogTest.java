package com.collectorstools.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test daty odczytywanej z nazwy pliku.
 *
 * <p>Data sporządzenia eksportu decyduje o roku w terminach zapisanych bez roku
 * („OBIETNICA SPŁATY 500 PLN 31,12"), więc podpowiedź musi trafiać albo cicho
 * ustępować dzisiejszej dacie — nigdy wywalać okna importu.
 */
class XlsxImportDialogTest {

    @BeforeAll
    static void initToolkit() throws Exception {
        FxTestSupport.startToolkit();
    }

    @Test
    void readsExportDateFromFileName() {
        assertEquals(LocalDate.of(2017, 2, 8),
                XlsxImportDialog.dateFromFileName("Zlecenie - Lubelskie 08.02.2017.xlsx"));
    }

    @Test
    void acceptsDashAndUnderscoreSeparators() {
        assertEquals(LocalDate.of(2016, 11, 15),
                XlsxImportDialog.dateFromFileName("zlecenie_15-11-2016.xlsx"));
        assertEquals(LocalDate.of(2016, 11, 15),
                XlsxImportDialog.dateFromFileName("zlecenie 15_11_2016.xlsx"));
    }

    @Test
    void fallsBackToTodayWhenNameHasNoDate() {
        assertEquals(LocalDate.now(), XlsxImportDialog.dateFromFileName("zlecenie.xlsx"));
    }

    /**
     * Liczby układające się w datę, której nie ma w kalendarzu — 31 lutego —
     * nie mogą przerwać otwierania okna.
     */
    @Test
    void fallsBackToTodayWhenNameHasImpossibleDate() {
        assertEquals(LocalDate.now(), XlsxImportDialog.dateFromFileName("zlecenie 31.02.2017.xlsx"));
    }
}
