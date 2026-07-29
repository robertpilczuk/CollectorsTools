package com.collectorstools.model;

/**
 * Rodzaj obiektu, którego dotyczy wpis audytowy.
 */
public enum AuditEntity {

    DEBTOR("Dłużnik"),
    DEBT("Sprawa"),
    DECLARATION("Deklaracja"),
    PAYMENT("Wpłata"),
    INTERACTION("Kontakt");

    private final String displayName;

    AuditEntity(String displayName) {
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
