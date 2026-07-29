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

    private String caseNumber;          // Nr sprawy
    private String creditor;            // Wierzyciel (firma zlecająca windykację)
    private BigDecimal totalAmount;     // Całkowita kwota długu
    private BigDecimal remainingAmount; // Pozostała kwota do spłaty
    private LocalDate debtDate;         // Data powstania długu
    private String description;         // Opis/tytuł długu (opcjonalnie)

    @Override
    public String toString() {
        return caseNumber + " — " + creditor + " (" + totalAmount + " PLN)";
    }
}
