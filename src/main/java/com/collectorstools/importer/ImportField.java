package com.collectorstools.importer;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Pole systemu, na które mapowana jest kolumna importowanego pliku.
 *
 * <p>Każdy wierzyciel nazywa kolumny po swojemu, dlatego pole zna listę synonimów
 * i potrafi rozpoznać nagłówek automatycznie. Dopasowanie pomija wielkość liter,
 * polskie znaki diakrytyczne i nadmiarowe spacje — nagłówki bywają zapisane
 * niekonsekwentnie nawet w obrębie jednego pliku.
 */
public enum ImportField {

    CASE_NUMBER("Nr sprawy", true, "nr sprawy", "numer sprawy", "sygnatura sprawy", "nr referencyjny"),
    DEBTOR_NAME("Nazwa dłużnika", true, "nazwa dluznika", "dluznik", "imie i nazwisko", "klient"),
    PESEL("PESEL", false, "pesel", "nr pesel"),

    POSTAL_CODE("Kod pocztowy", false, "kod pocztowy", "kod"),
    CITY("Miejscowość", false, "miejscowosc", "miasto"),
    STREET("Ulica", false, "ulica"),
    HOUSE_NUMBER("Nr domu", false, "nr domu", "numer domu"),
    FLAT_NUMBER("Nr lokalu", false, "nr lokalu", "numer lokalu", "nr mieszkania"),
    SECONDARY_ADDRESS("Adres 2", false, "adres 2", "adres korespondencyjny"),

    BALANCE("Saldo aktualne", true, "saldo aktualne", "saldo", "kwota zadluzenia", "do splaty"),
    PRINCIPAL("Kapitał", false, "kapital"),
    INTEREST("Odsetki", false, "odsetki"),
    COSTS("Koszty", false, "koszty"),

    ORIGINAL_CREDITOR("Wierzyciel pierwotny", false, "wierzyciel pierwotny"),
    CREDITOR("Wierzyciel obecny", true, "wierzyciel obecny", "wierzyciel", "zleceniodawca"),
    PORTFOLIO("Portfel", false, "portfel", "zlecenie"),
    PRODUCT("produkt", false, "produkt", "rodzaj produktu"),
    BANK_ACCOUNT("Konto", false, "konto", "rachunek", "nr rachunku"),

    COURT("Sąd", false, "sad"),
    COURT_SIGNATURE("Sygnatura sądowa", false, "sygnatura sadowa", "sygn. akt"),

    PHONES("Telefony", false, "telefony", "telefon", "nr telefonu"),
    FIELD_VISIT("czy była wizyta terenowa", false, "czy byla wizyta terenowa", "wizyta terenowa"),
    VISIT_NOTE("notatka z wizyty", false, "notatka z wizyty"),

    SERVICE_START_DATE("Data rozpoczęcia obsługi", false, "data rozpoczecia obslugi", "data zlecenia"),
    COMMENT("Komentarz do sprawy", false, "komentarz do sprawy", "komentarz", "uwagi");

    private final String displayName;
    private final boolean required;
    private final List<String> synonyms;

    ImportField(String displayName, boolean required, String... synonyms) {
        this.displayName = displayName;
        this.required = required;
        this.synonyms = List.of(synonyms);
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Czy bez tego pola import nie ma sensu. Sprawa bez numeru, wierzyciela, dłużnika
     * albo salda nie nadaje się do obsługi.
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Czy nagłówek jest dokładnie jednym z synonimów pola.
     */
    public boolean matchesExactly(String header) {
        String normalised = normalise(header);
        return !normalised.isEmpty() && synonyms.contains(normalised);
    }

    /**
     * Czy nagłówek zawiera któryś z synonimów.
     *
     * <p>Dopasowanie luźne bywa zdradliwe: synonim {@code sad} pola „Sąd" mieści się
     * w nagłówku „Sygnatura sądowa". Dlatego wolno go użyć dopiero wtedy, gdy żadne
     * pole nie zgłasza się do danej kolumny z dopasowaniem dokładnym —
     * patrz {@link HeaderMapping#autoDetect}.
     */
    public boolean matchesLoosely(String header) {
        String normalised = normalise(header);
        if (normalised.isEmpty()) {
            return false;
        }
        return synonyms.stream().anyMatch(normalised::contains);
    }

    /**
     * Sprowadza nagłówek do postaci porównywalnej: małe litery, bez znaków
     * diakrytycznych, bez nadmiarowych spacji.
     */
    public static String normalise(String header) {
        if (header == null) {
            return "";
        }
        String lower = header.strip().toLowerCase(Locale.ROOT).replace("ł", "l");
        String withoutMarks = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutMarks.replaceAll("\\s+", " ").strip();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
