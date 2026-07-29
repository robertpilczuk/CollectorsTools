package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interaction {

    private Long id;
    private Long debtorId;

    private LocalDate interactionDate; // Data kontaktu
    private ContactType contactType;   // Typ: WIZYTA / TELEFON / PISMO / EMAIL / INNE
    private String notes;              // Notatka z kontaktu
}
