package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Wpłata dłużnika na poczet konkretnej sprawy.
 *
 * <p>Wpłata zawsze wskazuje sprawę, bo to sprawa ma saldo. Powiązanie z deklaracją
 * jest opcjonalne — dłużnik może wpłacić bez wcześniejszej obietnicy, a windykator
 * i tak musi taką wpłatę zaewidencjonować.
 *
 * <p>Pola {@code allocated*} zapisują podział wpłaty między koszty, odsetki i kapitał
 * według kolejności zaliczania z art. 451 Kodeksu cywilnego. Zapamiętanie podziału
 * pozwala wycofać błędną wpłatę i odtworzyć saldo dokładnie w poprzedniej postaci.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    /** Wpłata wprowadzona ręcznie przez windykatora. */
    public static final String SOURCE_MANUAL = "RECZNIE";

    /** Wpłata odtworzona z importu danych wierzyciela. */
    public static final String SOURCE_IMPORT = "IMPORT";

    private Long id;
    private Long debtId;         // Wymagane — saldo należy do sprawy
    private Long declarationId;  // Opcjonalne — wpłata na poczet konkretnej obietnicy

    private BigDecimal amount;
    private LocalDate paymentDate;

    private BigDecimal allocatedCost;
    private BigDecimal allocatedInterest;
    private BigDecimal allocatedPrincipal;

    private String note;
    private String source;
    private LocalDate createdAt;
    private String userId;       // Wypełniane po wprowadzeniu logowania

    @Override
    public String toString() {
        return paymentDate + " — " + amount + " PLN";
    }
}
