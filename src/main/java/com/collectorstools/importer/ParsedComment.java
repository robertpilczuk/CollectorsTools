package com.collectorstools.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Zawartość kolumny „Komentarz do sprawy" rozłożona na pola.
 *
 * <p>Pole jest półstrukturalne: sekcje rozdzielone średnikami mają stałe etykiety
 * i dają się odczytać pewnie, natomiast treść ostatniego kontaktu z klientem to
 * swobodna notatka windykatora, z której obietnicę spłaty da się wyciągnąć tylko
 * heurystycznie.
 *
 * @param warnings zastrzeżenia do odczytu — trafiają do podglądu importu,
 *                 żeby użytkownik zobaczył, czego parser nie był pewny
 */
public record ParsedComment(
        String portfolio,
        String caseCategory,
        String creditStatus,
        Boolean settlementAllowed,
        BigDecimal creditAmount,

        String employerName,
        String employerAddress,
        String employerPhone,

        LocalDate lastPaymentDate,
        BigDecimal lastPaymentAmount,
        LocalDate enforcementStartDate,

        String note,
        String lastContact,

        PromiseOfPayment promise,
        List<String> warnings) {

    public boolean hasLastPayment() {
        return lastPaymentDate != null && lastPaymentAmount != null;
    }

    public boolean hasPromise() {
        return promise != null;
    }

    public boolean hasEmployer() {
        return employerName != null && !employerName.isBlank();
    }
}
