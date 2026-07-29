package com.collectorstools.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PeselValidatorTest {

    @Test
    void acceptsValidPesel() {
        // 44051401359 — klasyczny poprawny PESEL (1944-05-14, mężczyzna)
        assertTrue(PeselValidator.isValid("44051401359"));
    }

    @Test
    void rejectsWrongChecksum() {
        assertFalse(PeselValidator.isValid("44051401358"));
    }

    @Test
    void rejectsWrongLengthOrNonDigits() {
        assertFalse(PeselValidator.isValid("123"));
        assertFalse(PeselValidator.isValid("4405140135X"));
        assertFalse(PeselValidator.isValid(null));
    }

    @Test
    void rejectsImpossibleDate() {
        // miesiąc 13 / dzień 32 — niepoprawna data
        assertFalse(PeselValidator.isValid("44131401357"));
    }

    @Test
    void extractsBirthDateAcrossCenturies() {
        assertEquals(LocalDate.of(1944, 5, 14), PeselValidator.birthDate("44051401359"));
        // rok 2000+ → miesiąc +20
        assertEquals(LocalDate.of(2002, 1, 1), PeselValidator.birthDate("02210100008"));
    }

    @Test
    void extractsGender() {
        assertEquals("Mężczyzna", PeselValidator.gender("44051401359"));
        // cyfra płci (10.) parzysta → kobieta
        assertEquals("Kobieta", PeselValidator.gender("44051401408"));
    }
}
