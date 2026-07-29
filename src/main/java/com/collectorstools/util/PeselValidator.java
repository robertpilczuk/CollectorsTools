package com.collectorstools.util;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Walidacja numeru PESEL (długość, cyfry, suma kontrolna, poprawna data)
 * oraz wyciąganie daty urodzenia i płci.
 */
public final class PeselValidator {

    private static final int[] WEIGHTS = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};

    private PeselValidator() {
    }

    public static boolean isValid(String pesel) {
        if (pesel == null || !pesel.matches("\\d{11}")) return false;
        if (birthDate(pesel) == null) return false;
        return checksumOk(pesel);
    }

    private static boolean checksumOk(String pesel) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (pesel.charAt(i) - '0') * WEIGHTS[i];
        }
        int control = (10 - (sum % 10)) % 10;
        return control == (pesel.charAt(10) - '0');
    }

    /** Data urodzenia z PESEL lub {@code null}, gdy data jest nieprawidłowa. */
    public static LocalDate birthDate(String pesel) {
        if (pesel == null || !pesel.matches("\\d{11}")) return null;
        int year = Integer.parseInt(pesel.substring(0, 2));
        int monthRaw = Integer.parseInt(pesel.substring(2, 4));
        int day = Integer.parseInt(pesel.substring(4, 6));

        int month;
        int century;
        if (monthRaw >= 1 && monthRaw <= 12)        { month = monthRaw;       century = 1900; }
        else if (monthRaw >= 21 && monthRaw <= 32)  { month = monthRaw - 20;  century = 2000; }
        else if (monthRaw >= 41 && monthRaw <= 52)  { month = monthRaw - 40;  century = 2100; }
        else if (monthRaw >= 61 && monthRaw <= 72)  { month = monthRaw - 60;  century = 2200; }
        else if (monthRaw >= 81 && monthRaw <= 92)  { month = monthRaw - 80;  century = 1800; }
        else return null;

        try {
            return LocalDate.of(century + year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** "Kobieta" / "Mężczyzna" lub {@code null} dla nieprawidłowego PESEL. */
    public static String gender(String pesel) {
        if (pesel == null || !pesel.matches("\\d{11}")) return null;
        int genderDigit = pesel.charAt(9) - '0';
        return (genderDigit % 2 == 0) ? "Kobieta" : "Mężczyzna";
    }
}
