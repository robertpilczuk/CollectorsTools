package com.collectorstools.model;

/**
 * Rodzaj operacji odnotowanej w dzienniku audytowym.
 *
 * <p>Operacje finansowe i decyzje o stanie sprawy mają osobne pozycje, bo to o nie
 * pyta się przy wyjaśnianiu rozbieżności — „kto i kiedy wycofał tę wpłatę" jest
 * pytaniem innej wagi niż „kto poprawił literówkę w opisie".
 */
public enum AuditOperation {

    CREATE("Utworzenie"),
    UPDATE("Zmiana danych"),
    DELETE("Usunięcie"),

    PAYMENT_REGISTERED("Rejestracja wpłaty"),
    PAYMENT_REVERSED("Wycofanie wpłaty"),

    DECLARATION_STATUS("Zmiana stanu deklaracji"),
    CASE_CLOSED("Zamknięcie sprawy"),
    CASE_REOPENED("Ponowne otwarcie sprawy");

    private final String displayName;

    AuditOperation(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Czy operacja dotyczy pieniędzy albo stanu sprawy — takie wpisy wyróżniamy
     * w interfejsie i nigdy ich nie ukrywamy.
     */
    public boolean isSignificant() {
        return this == PAYMENT_REGISTERED || this == PAYMENT_REVERSED
                || this == CASE_CLOSED || this == CASE_REOPENED || this == DELETE;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
