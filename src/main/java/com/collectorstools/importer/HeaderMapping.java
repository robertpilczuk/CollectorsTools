package com.collectorstools.importer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Przypisanie kolumn pliku do pól systemu.
 *
 * <p>Mapowanie powstaje automatycznie na podstawie nagłówków, ale każde przypisanie
 * można nadpisać ręcznie — plik od nowego wierzyciela prędzej czy później nazwie
 * kolumnę inaczej niż przewiduje słownik synonimów, a wtedy użytkownik musi mieć
 * możliwość wskazania jej samodzielnie, bez zmiany w kodzie.
 */
public class HeaderMapping {

    private final List<String> headers;
    private final Map<ImportField, Integer> mapping = new EnumMap<>(ImportField.class);

    private HeaderMapping(List<String> headers) {
        this.headers = List.copyOf(headers);
    }

    /**
     * Dopasowuje nagłówki do pól systemu.
     *
     * <p>Dwa przebiegi: najpierw dopasowania dokładne dla wszystkich pól, dopiero potem
     * częściowe dla tych, które zostały bez przypisania. Kolejność ma znaczenie —
     * synonim {@code sad} pola „Sąd" mieści się w nagłówku „Sygnatura sądowa", więc
     * przy jednym przebiegu pole „Sąd" zabierało kolumnę sygnatury, a właściwa
     * kolumna „Sąd" zostawała nieprzypisana. Błąd ujawnił się dopiero na rzeczywistym
     * pliku, w którym obie kolumny występują obok siebie.
     */
    public static HeaderMapping autoDetect(List<String> headers) {
        HeaderMapping result = new HeaderMapping(headers);

        result.assignPass(headers, ImportField::matchesExactly);
        result.assignPass(headers, ImportField::matchesLoosely);

        return result;
    }

    private void assignPass(List<String> headers, HeaderPredicate predicate) {
        for (ImportField field : ImportField.values()) {
            if (mapping.containsKey(field)) {
                continue;
            }
            for (int column = 0; column < headers.size(); column++) {
                if (mapping.containsValue(column)) {
                    continue;
                }
                if (predicate.test(field, headers.get(column))) {
                    mapping.put(field, column);
                    break;
                }
            }
        }
    }

    @FunctionalInterface
    private interface HeaderPredicate {
        boolean test(ImportField field, String header);
    }

    /**
     * Ręczne wskazanie kolumny. {@code null} usuwa przypisanie.
     */
    public void assign(ImportField field, Integer column) {
        if (column == null) {
            mapping.remove(field);
            return;
        }
        if (column < 0 || column >= headers.size()) {
            throw new IllegalArgumentException("Kolumna poza zakresem pliku: " + column);
        }
        mapping.entrySet().removeIf(entry -> entry.getValue().equals(column));
        mapping.put(field, column);
    }

    public Integer columnOf(ImportField field) {
        return mapping.get(field);
    }

    public boolean isMapped(ImportField field) {
        return mapping.containsKey(field);
    }

    public List<String> getHeaders() {
        return headers;
    }

    /**
     * Pola wymagane, których nie udało się dopasować. Dopóki lista nie jest pusta,
     * importu nie wolno uruchomić.
     */
    public List<ImportField> missingRequiredFields() {
        List<ImportField> missing = new ArrayList<>();
        for (ImportField field : ImportField.values()) {
            if (field.isRequired() && !isMapped(field)) {
                missing.add(field);
            }
        }
        return missing;
    }

    /**
     * Kolumny pliku, które nie trafiły do żadnego pola — informacja dla użytkownika,
     * że ich zawartość zostanie pominięta.
     */
    public List<String> unmappedHeaders() {
        List<String> unmapped = new ArrayList<>();
        for (int column = 0; column < headers.size(); column++) {
            if (!mapping.containsValue(column)) {
                unmapped.add(headers.get(column));
            }
        }
        return unmapped;
    }

    public boolean isReady() {
        return missingRequiredFields().isEmpty();
    }

    /**
     * Podsumowanie dopasowania do pokazania w oknie importu.
     */
    public Map<ImportField, String> describe() {
        Map<ImportField, String> description = new LinkedHashMap<>();
        for (ImportField field : ImportField.values()) {
            Integer column = mapping.get(field);
            description.put(field, column != null ? headers.get(column) : null);
        }
        return description;
    }
}
