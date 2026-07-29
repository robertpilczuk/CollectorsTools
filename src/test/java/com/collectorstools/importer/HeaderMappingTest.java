package com.collectorstools.importer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderMappingTest {

    /** Nagłówki z rzeczywistego eksportu wierzyciela, w oryginalnej kolejności. */
    private static final List<String> REAL_HEADERS = List.of(
            "Nr sprawy", "czy była wizyta terenowa", "Nazwa dłużnika", "PESEL", "Kod pocztowy",
            "Miejscowość", "Ulica", "Nr domu", "Nr lokalu", "Adres 2", "Adres 3",
            "Saldo aktualne", "Kapitał", "Odsetki", "Koszty", "Wierzyciel pierwotny", "Portfel",
            "Wierzyciel obecny", "Sygnatura sądowa", "Sąd", "Telefony", "Konto", "produkt",
            "Data rozpoczęcia obsługi", "Komentarz do sprawy", "notatka z wizyty");

    @Test
    @DisplayName("Rozpoznaje układ rzeczywistego eksportu")
    void detectsRealExportLayout() {
        HeaderMapping mapping = HeaderMapping.autoDetect(REAL_HEADERS);

        assertTrue(mapping.isReady(), "Brakujące pola: " + mapping.missingRequiredFields());
        assertEquals(0, mapping.columnOf(ImportField.CASE_NUMBER));
        assertEquals(2, mapping.columnOf(ImportField.DEBTOR_NAME));
        assertEquals(3, mapping.columnOf(ImportField.PESEL));
        assertEquals(11, mapping.columnOf(ImportField.BALANCE));
        assertEquals(12, mapping.columnOf(ImportField.PRINCIPAL));
        assertEquals(17, mapping.columnOf(ImportField.CREDITOR));
        assertEquals(24, mapping.columnOf(ImportField.COMMENT));
    }

    @Test
    @DisplayName("Sąd nie jest mylony z sygnaturą sądową")
    void distinguishesCourtFromCourtSignature() {
        HeaderMapping mapping = HeaderMapping.autoDetect(REAL_HEADERS);

        // Regresja: synonim "sad" mieści się w "sygnatura sadowa", więc przy jednym
        // przebiegu pole Sąd zabierało kolumnę sygnatury, a kolumna Sąd zostawała pusta
        assertEquals(18, mapping.columnOf(ImportField.COURT_SIGNATURE));
        assertEquals(19, mapping.columnOf(ImportField.COURT));
        assertFalse(mapping.unmappedHeaders().contains("Sąd"));
        assertFalse(mapping.unmappedHeaders().contains("Sygnatura sądowa"));
    }

    @Test
    @DisplayName("Dopasowanie dokładne ma pierwszeństwo przed częściowym")
    void exactMatchWinsOverPartial() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of(
                "Nr sprawy", "Nazwa dłużnika", "Wierzyciel", "Saldo po korekcie", "Saldo"));

        assertEquals(4, mapping.columnOf(ImportField.BALANCE),
                "Kolumna nazwana dokładnie \"Saldo\" wygrywa z \"Saldo po korekcie\"");
    }

    @Test
    @DisplayName("Wierzyciel obecny nie jest mylony z pierwotnym")
    void distinguishesCurrentAndOriginalCreditor() {
        HeaderMapping mapping = HeaderMapping.autoDetect(REAL_HEADERS);

        assertEquals(15, mapping.columnOf(ImportField.ORIGINAL_CREDITOR));
        assertEquals(17, mapping.columnOf(ImportField.CREDITOR));
    }

    @Test
    @DisplayName("Dopasowanie pomija wielkość liter, znaki diakrytyczne i spacje")
    void matchingIgnoresCaseDiacriticsAndSpacing() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of(
                "  NR SPRAWY  ", "nazwa dluznika", "SALDO", "Wierzyciel"));

        assertTrue(mapping.isReady());
        assertEquals(0, mapping.columnOf(ImportField.CASE_NUMBER));
        assertEquals(1, mapping.columnOf(ImportField.DEBTOR_NAME));
        assertEquals(2, mapping.columnOf(ImportField.BALANCE));
        assertEquals(3, mapping.columnOf(ImportField.CREDITOR));
    }

    @Test
    @DisplayName("Rozpoznaje synonimy z innego wierzyciela")
    void matchesAlternativeNaming() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of(
                "Numer sprawy", "Klient", "Kwota zadłużenia", "Zleceniodawca", "Nr telefonu"));

        assertTrue(mapping.isReady());
        assertEquals(4, mapping.columnOf(ImportField.PHONES));
    }

    @Test
    @DisplayName("Brakujące pole wymagane blokuje import")
    void missingRequiredFieldBlocksImport() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of("Nr sprawy", "Nazwa dłużnika", "Saldo"));

        assertFalse(mapping.isReady());
        assertEquals(List.of(ImportField.CREDITOR), mapping.missingRequiredFields());
    }

    @Test
    @DisplayName("Jedna kolumna nie trafia do dwóch pól")
    void columnIsAssignedOnlyOnce() {
        HeaderMapping mapping = HeaderMapping.autoDetect(REAL_HEADERS);

        long distinctColumns = mapping.describe().values().stream()
                .filter(header -> header != null)
                .distinct().count();
        long assignedFields = mapping.describe().values().stream()
                .filter(header -> header != null).count();

        assertEquals(assignedFields, distinctColumns);
    }

    @Test
    @DisplayName("Kolumny bez dopasowania są zgłaszane")
    void reportsUnmappedColumns() {
        HeaderMapping mapping = HeaderMapping.autoDetect(REAL_HEADERS);

        assertTrue(mapping.unmappedHeaders().contains("Adres 3"),
                "Kolumna bez odpowiednika musi być widoczna dla użytkownika: "
                        + mapping.unmappedHeaders());
    }

    @Test
    @DisplayName("Ręczne wskazanie kolumny nadpisuje dopasowanie automatyczne")
    void manualAssignmentOverridesDetection() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of(
                "Nr sprawy", "Nazwa dłużnika", "Saldo", "Wierzyciel", "Uwagi handlowe"));

        mapping.assign(ImportField.COMMENT, 4);
        assertEquals(4, mapping.columnOf(ImportField.COMMENT));

        mapping.assign(ImportField.COMMENT, null);
        assertNull(mapping.columnOf(ImportField.COMMENT));
    }

    @Test
    @DisplayName("Wskazanie zajętej kolumny zwalnia ją z poprzedniego pola")
    void reassigningColumnReleasesPreviousField() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of(
                "Nr sprawy", "Nazwa dłużnika", "Saldo", "Wierzyciel"));

        mapping.assign(ImportField.PORTFOLIO, 2);

        assertEquals(2, mapping.columnOf(ImportField.PORTFOLIO));
        assertNull(mapping.columnOf(ImportField.BALANCE), "Kolumna nie może należeć do dwóch pól");
    }

    @Test
    @DisplayName("Kolumna spoza zakresu pliku jest odrzucana")
    void rejectsColumnOutsideFile() {
        HeaderMapping mapping = HeaderMapping.autoDetect(List.of("Nr sprawy", "Saldo"));

        assertThrows(IllegalArgumentException.class, () -> mapping.assign(ImportField.CITY, 9));
    }
}
