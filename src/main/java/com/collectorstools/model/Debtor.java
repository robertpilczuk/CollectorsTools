package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Debtor {

    private Long id;

    // Dane osobowe
    private String firstName;
    private String lastName;
    private String pesel;
    private String idNumber;       // Nr dowodu osobistego (opcjonalnie)

    // Kontakt
    private String phone;
    private String email;

    // Adres
    private String street;
    private String city;
    private String postalCode;

    // Notatki ogólne
    private String notes;

    // Oznaczenie "pilne" — wyróżnia dłużnika na liście
    private boolean urgent;

    public String getFullName() {
        return firstName + " " + lastName;
    }

    @Override
    public String toString() {
        return getFullName() + " (" + pesel + ")";
    }
}
