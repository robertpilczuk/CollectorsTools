package com.collectorstools.model;

/**
 * Stan sprawy windykacyjnej.
 *
 * <p>Do tej pory sprawa spłacona była nieodróżnialna od aktywnej — jedyną
 * przesłanką było zerowe saldo, co nie mówiło nic o sprawach umorzonych,
 * przedawnionych czy skierowanych na drogę sądową.
 *
 * <p>Kategorie zamknięcia odpowiadają temu, co realnie dzieje się z portfelem:
 * eksport wierzyciela z lutego 2017 zawierał między innymi sprawy oznaczone
 * jako przedawnione ({@code Z_PRZEDAWN}) i sprawy egzekucyjne.
 */
public enum DebtStatus {

    /** Sprawa w obsłudze. */
    ACTIVE("Aktywna"),

    /** Saldo spłacone w całości. */
    CLOSED_PAID("Zamknięta — spłacona"),

    /** Wierzyciel odstąpił od dochodzenia należności. */
    CLOSED_WRITTEN_OFF("Zamknięta — umorzona"),

    /** Sprawa skierowana na drogę sądową lub do komornika. */
    CLOSED_LEGAL("Zamknięta — droga sądowa"),

    /** Roszczenie przedawnione. */
    CLOSED_TIME_BARRED("Zamknięta — przedawniona");

    private final String displayName;

    DebtStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isClosed() {
        return this != ACTIVE;
    }

    /**
     * Czy zamknięcie w tym stanie wymaga dodatniego salda równego zeru.
     * Sprawy spłaconej nie da się zamknąć z niespłaconą resztą.
     */
    public boolean requiresZeroBalance() {
        return this == CLOSED_PAID;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
