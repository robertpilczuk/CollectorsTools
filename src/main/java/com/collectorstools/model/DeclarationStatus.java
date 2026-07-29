package com.collectorstools.model;

/**
 * Stan deklaracji spłaty.
 *
 * <p>Nazwy stałych i wartości zapisywane w bazie są angielskie; polskie brzmienie
 * służy wyłącznie prezentacji w interfejsie.
 *
 * <p>Przejścia:
 * <pre>
 *   PENDING ──── wpłata pełna ──────────→ FULFILLED
 *           ──── wpłata częściowa ──────→ PARTIALLY_FULFILLED ── dopłata → FULFILLED
 *           ──── minął termin ──────────→ BROKEN ── wpłata po terminie → FULFILLED
 *           ──── anulowanie z powodem ──→ CANCELLED
 * </pre>
 */
public enum DeclarationStatus {

    /** Termin jeszcze nie minął, wpłaty brak. */
    PENDING("Oczekująca"),

    /** Zadeklarowana kwota wpłacona w całości. */
    FULFILLED("Dotrzymana"),

    /** Wpłacono część zadeklarowanej kwoty. */
    PARTIALLY_FULFILLED("Częściowo dotrzymana"),

    /** Termin minął bez wpłaty albo windykator oznaczył niedotrzymanie z powodem. */
    BROKEN("Złamana"),

    /** Wycofana z ewidencji — zawsze z uzasadnieniem. */
    CANCELLED("Anulowana");

    private final String displayName;

    DeclarationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Czy deklaracja czeka jeszcze na rozstrzygnięcie — wtedy można rejestrować
     * do niej wpłaty i zmieniać jej stan.
     */
    public boolean isOpen() {
        return this == PENDING || this == PARTIALLY_FULFILLED;
    }

    /**
     * Czy stan wchodzi do wskaźnika skuteczności. Deklaracje oczekujące i anulowane
     * są z niego wyłączone — pierwsze jeszcze się nie rozstrzygnęły, drugie zostały
     * wycofane i nie świadczą o zachowaniu dłużnika.
     */
    public boolean countsTowardsEffectiveness() {
        return this == FULFILLED || this == PARTIALLY_FULFILLED || this == BROKEN;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
