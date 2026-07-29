package com.collectorstools.importer;

/**
 * Na ile można ufać odczytanej wartości.
 *
 * <p>Komentarz do sprawy jest polem swobodnym — windykatorzy zapisywali obietnice
 * w kilkunastu różnych układach. Parser nigdy nie zgaduje po cichu: wartość
 * niepewna dostaje niższy poziom i trafia do ręcznego przeglądu, zamiast wjechać
 * do bazy jako fakt.
 */
public enum ParseConfidence {

    /** Kwota i data odczytane jednoznacznie. */
    HIGH("Pewne"),

    /** Odczytana kwota, ale terminu nie da się ustalić. */
    MEDIUM("Wymaga uzupełnienia terminu"),

    /** Zapis nieczytelny dla parsera — do decyzji człowieka. */
    LOW("Wymaga sprawdzenia");

    private final String displayName;

    ParseConfidence(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
