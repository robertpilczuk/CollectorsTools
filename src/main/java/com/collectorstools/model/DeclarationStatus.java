package com.collectorstools.model;

public enum DeclarationStatus {
    OCZEKUJACA("Oczekująca"),
    DOTRZYMANA("Dotrzymana"),
    ZLAMANA("Złamana");

    private final String displayName;

    DeclarationStatus(String displayName) {
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
