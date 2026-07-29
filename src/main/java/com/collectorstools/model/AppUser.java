package com.collectorstools.model;

/**
 * Użytkownik wykonujący operacje w aplikacji.
 *
 * <p>W obecnej wersji istnieje jeden użytkownik lokalny — aplikacja nie ma logowania.
 * Typ powstał po to, żeby każdy wpis audytowy miał od początku przypisanego autora;
 * dodanie logowania i ról sprowadzi się do podmiany źródła tej wartości, bez migracji
 * danych i bez zmian w serwisach.
 */
public record AppUser(String id, String displayName) {

    public static AppUser local() {
        return new AppUser("local", "Użytkownik lokalny");
    }
}
