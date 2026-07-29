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

    private LocalDate createdAt;       // Kiedy deklaracja została zarejestrowana
    private LocalDate promisedDate;    // Data obietnicy wpłaty
    private BigDecimal promisedAmount; // Obiecana kwota
    private DeclarationStatus status;

    private String brokenReason;       // Powód złamania (wypełniany przez windykatora)
    private String cancelledReason;    // Uzasadnienie anulowania — wymagane przy CANCELLED
    private LocalDate paymentDate;     // Data faktycznej wpłaty

    public boolean isBroken() {
        return status == DeclarationStatus.BROKEN;
    }

    public boolean isPending() {
        return status == DeclarationStatus.PENDING;
    }

    /**
     * Czy wpłata wpłynęła po zadeklarowanym terminie. Deklaracja może być
     * dotrzymana mimo opóźnienia — interfejs oznacza taki przypadek osobno.
     */
    public boolean isPaidLate() {
        return paymentDate != null
                && promisedDate != null
                && paymentDate.isAfter(promisedDate);
    }

    /**
     * Powód zmiany stanu — złamania albo anulowania, zależnie od statusu.
     */
    public String getReason() {
        if (status == DeclarationStatus.CANCELLED) return cancelledReason;
        return brokenReason;
    }

    @Override
    public String toString() {
        return promisedDate + " — " + promisedAmount + " PLN [" + status + "]";
    }
}
