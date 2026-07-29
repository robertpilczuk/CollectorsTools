package com.collectorstools.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Jeden wiersz pliku sprowadzony do postaci, w jakiej trafi do bazy.
 *
 * <p>Wiersz niesie też własną ocenę: czy nadaje się do importu i co budzi wątpliwości.
 * Podgląd pokazuje to użytkownikowi przed zapisem — import, który po cichu odrzuca albo
 * po cichu poprawia dane, jest gorszy od importu, który się nie udał.
 */
@Data
public class ImportRow {

    /** Numer wiersza w pliku, licząc od 1 wraz z nagłówkiem — tak jak widzi go Excel. */
    private int sourceRowNumber;

    // Dłużnik
    private String firstName;
    private String lastName;
    private String pesel;
    private String street;
    private String houseNumber;
    private String flatNumber;
    private String postalCode;
    private String city;
    private String secondaryAddress;
    private List<String> phones = new ArrayList<>();

    // Sprawa
    private String caseNumber;
    private String creditor;
    private String originalCreditor;
    private String portfolio;
    private String product;
    private String bankAccount;
    private String court;
    private String courtSignature;
    private BigDecimal balance;
    private BigDecimal principal;
    private BigDecimal interest;
    private BigDecimal costs;
    private LocalDate serviceStartDate;

    // Wizyta terenowa
    private boolean fieldVisit;
    private String visitNote;

    // Dane odczytane z komentarza
    private ParsedComment comment;

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /** Czy wiersz nadaje się do zapisu. */
    private boolean importable = true;

    /** Czy PESEL wiersza wskazuje na dłużnika już obecnego w bazie. */
    private boolean existingDebtor;

    public void reject(String reason) {
        errors.add(reason);
        importable = false;
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public String getFullName() {
        return (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
    }

    /**
     * Krótki opis stanu wiersza dla kolumny „Status" w podglądzie.
     */
    public String getStatusDescription() {
        if (!errors.isEmpty()) {
            return errors.get(0);
        }
        if (existingDebtor) {
            return warnings.isEmpty()
                    ? "OK — kolejna sprawa istniejącego dłużnika"
                    : "Kolejna sprawa; " + warnings.get(0);
        }
        return warnings.isEmpty() ? "OK" : warnings.get(0);
    }

    /**
     * Adres złożony z osobnych kolumn pliku.
     */
    public String getStreetAddress() {
        StringBuilder address = new StringBuilder();
        if (street != null && !street.isBlank()) address.append(street);
        if (houseNumber != null && !houseNumber.isBlank()) address.append(" ").append(houseNumber);
        if (flatNumber != null && !flatNumber.isBlank()) address.append("/").append(flatNumber);
        return address.toString().strip();
    }
}
