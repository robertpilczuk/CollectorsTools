package com.collectorstools.model;

public enum ContactType {
    WIZYTA("Wizyta terenowa"),
    TELEFON("Telefon"),
    PISMO("Pismo"),
    EMAIL("E-mail"),
    INNE("Inne");

    private final String displayName;

    ContactType(String displayName) {
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
