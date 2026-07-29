package com.collectorstools.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Obietnica spłaty odczytana z notatki windykatora.
 *
 * @param amount     obiecana kwota
 * @param dueDate    termin — {@code null}, gdy w notatce go nie było albo był nieczytelny
 * @param confidence na ile odczyt jest pewny
 * @param rawText    oryginalny fragment notatki, żeby dało się sprawdzić odczyt
 */
public record PromiseOfPayment(BigDecimal amount, LocalDate dueDate,
                               ParseConfidence confidence, String rawText) {

    public boolean hasDueDate() {
        return dueDate != null;
    }
}
