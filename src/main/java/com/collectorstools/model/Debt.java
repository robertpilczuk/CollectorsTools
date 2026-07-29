package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Debt {

    private Long id;
    private Long debtorId;

    // Identyfikacja sprawy
    private String caseNumber;          // Nr sprawy
    private String creditor;            // Wierzyciel zlecający windykację
    private String originalCreditor;    // Wierzyciel pierwotny (jeśli dług był zbyty)
    private String portfolio;           // Portfel/zlecenie, w ramach którego sprawa wpłynęła
    private String product;             // Produkt bankowy (kredyt, karta itp.)
    private String bankAccount;         // Rachunek do wpłat

    // Kwoty — saldo rozbite na składniki
    private BigDecimal totalAmount;     // Całkowita kwota długu
    private BigDecimal remainingAmount; // Pozostała kwota do spłaty
    private BigDecimal principalAmount; // Kapitał
    private BigDecimal interestAmount;  // Odsetki
    private BigDecimal costAmount;      // Koszty

    // Etap i charakterystyka sprawy
    private String caseCategory;        // Kategoria/etap (przedegzekucyjna, egzekucyjna, przedawniona...)
    private String creditStatus;        // Status kredytu u wierzyciela
    private Boolean settlementAllowed;  // Czy wierzyciel dopuszcza ugodę

    // Postępowanie sądowe
    private String court;
    private String courtSignature;      // Sygnatura sądowa

    // Daty
    private LocalDate debtDate;              // Data powstania długu
    private LocalDate serviceStartDate;      // Data rozpoczęcia obsługi sprawy
    private LocalDate enforcementStartDate;  // Data wszczęcia egzekucji

    private String description;         // Opis/tytuł długu (opcjonalnie)

    /**
     * Suma składników salda. Powinna odpowiadać {@code remainingAmount} —
     * niezmiennik potwierdzony na eksporcie wierzyciela i pilnowany
     * przez walidację w serwisie.
     */
    public BigDecimal getComponentsSum() {
        return nz(principalAmount).add(nz(interestAmount)).add(nz(costAmount));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    @Override
    public String toString() {
        return caseNumber + " — " + creditor + " (" + totalAmount + " PLN)";
    }
}
