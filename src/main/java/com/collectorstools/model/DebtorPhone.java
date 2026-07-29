package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Numer telefonu dłużnika.
 *
 * <p>Eksporty wierzycieli podają zwykle kilka numerów na sprawę (w analizowanym
 * portfelu do dziesięciu). Trzymanie ich w osobnej tabeli zamiast w jednym polu
 * tekstowym pozwala wyszukiwać dłużnika po dowolnym numerze i odnotować, skąd
 * numer pochodzi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtorPhone {

    private Long id;
    private Long debtorId;

    private String phoneNumber;
    private boolean primary;   // Numer podstawowy — pokazywany na liście dłużników
    private String source;     // Skąd numer pochodzi: IMPORT, RECZNIE, MIGRACJA

    @Override
    public String toString() {
        return phoneNumber + (primary ? " (główny)" : "");
    }
}
