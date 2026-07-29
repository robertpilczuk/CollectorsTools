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
public class Declaration {

    private Long id;
    private Long debtorId;
    private Long debtId;               // Do której sprawy należy (opcjonalnie)

    private LocalDate promisedDate;    // Data obietnicy wpłaty
    private BigDecimal promisedAmount; // Obiecana kwota
    private DeclarationStatus status;  // OCZEKUJACA / DOTRZYMANA / ZLAMANA

    private String brokenReason;       // Powód złamania (wypełniany przez windykatora)
    private LocalDate paymentDate;     // Data faktycznej wpłaty (jeśli dotrzymana)

    /**
     * Automatycznie ustawia status na ZLAMANA jeśli data minęła
     * i deklaracja nadal oczekuje na wpłatę.
     */
    public void refreshStatus() {
        if (status == DeclarationStatus.OCZEKUJACA
                && promisedDate != null
                && LocalDate.now().isAfter(promisedDate)) {
            status = DeclarationStatus.ZLAMANA;
        }
    }

    public boolean isBroken() {
        return status == DeclarationStatus.ZLAMANA;
    }

    public boolean isPending() {
        return status == DeclarationStatus.OCZEKUJACA;
    }

    @Override
    public String toString() {
        return promisedDate + " — " + promisedAmount + " PLN [" + status + "]";
    }
}
