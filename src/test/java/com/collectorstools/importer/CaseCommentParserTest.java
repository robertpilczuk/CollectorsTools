package com.collectorstools.importer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wzorce w testach pochodzą z rzeczywistego eksportu wierzyciela (171 spraw,
 * luty 2017). Numery telefonów zostały zastąpione, reszta zapisu jest oryginalna —
 * to właśnie jej nieregularność jest tu przedmiotem testu.
 */
class CaseCommentParserTest {

    /** Data sporządzenia eksportu — służy do ustalenia roku w terminach bez roku. */
    private static final LocalDate EXPORT_DATE = LocalDate.of(2017, 2, 8);

    private CaseCommentParser parser;

    @BeforeEach
    void setUp() {
        parser = new CaseCommentParser();
    }

    private ParsedComment parse(String comment) {
        return parser.parse(comment, EXPORT_DATE);
    }

    private static final String FULL_COMMENT = """
            Portfel: DETAL ; kategoria sprawy: EGZEKUCJE 2 KAT C - sprawy egzekucyjne poagencyjne \
            trudniejsze; zezwolenie na zawarcie ugody: TAK; status kredytu: PO OKRESIE WYPOWIEDZENIA; \
            Miejsce zatrudnienia: CENTRUM KULTURY W LUBLINIE, adres zatrudnienia:LUBLIN NARUTOWICZA 32,\
            tel. do pracy:815360322;data i kwota ostatniej wpłaty: 2016-06-16 81,89; \
            Data wszczęcia egzekucji: 2015-02-17; treść notatki: ; \
            treść ostatniego kontaktu z klientem: WT 600100200  KONTAKT Z KLIENTEM  \
            OBIETNICA SPŁATY 50 PLN 31,12 / DOCHOD DZIALALNOSC""";

    // ===== sekcje z etykietami =====

    @Test
    @DisplayName("Odczytuje wszystkie sekcje z etykietami")
    void readsLabelledSections() {
        ParsedComment parsed = parse(FULL_COMMENT);

        assertEquals("DETAL", parsed.portfolio());
        assertTrue(parsed.caseCategory().startsWith("EGZEKUCJE 2 KAT C"));
        assertEquals("PO OKRESIE WYPOWIEDZENIA", parsed.creditStatus());
        assertEquals(Boolean.TRUE, parsed.settlementAllowed());
        assertEquals("CENTRUM KULTURY W LUBLINIE", parsed.employerName());
        assertEquals("LUBLIN NARUTOWICZA 32", parsed.employerAddress());
        assertEquals("815360322", parsed.employerPhone());
        assertEquals(LocalDate.of(2015, 2, 17), parsed.enforcementStartDate());
    }

    @Test
    @DisplayName("Odczytuje datę i kwotę ostatniej wpłaty")
    void readsLastPayment() {
        ParsedComment parsed = parse(FULL_COMMENT);

        assertTrue(parsed.hasLastPayment());
        assertEquals(LocalDate.of(2016, 6, 16), parsed.lastPaymentDate());
        assertEquals(0, new BigDecimal("81.89").compareTo(parsed.lastPaymentAmount()));
    }

    @Test
    @DisplayName("Kwoty bez części dziesiętnej też są odczytywane")
    void readsWholeAmounts() {
        ParsedComment parsed = parse("data i kwota ostatniej wpłaty: 2016-04-11 800;");

        assertEquals(0, new BigDecimal("800").compareTo(parsed.lastPaymentAmount()));
    }

    @Test
    @DisplayName("Zgoda na ugodę rozróżnia TAK, NIE i brak informacji")
    void readsSettlementPermission() {
        assertEquals(Boolean.TRUE, parse("zezwolenie na zawarcie ugody: TAK;").settlementAllowed());
        assertEquals(Boolean.FALSE, parse("zezwolenie na zawarcie ugody: NIE;").settlementAllowed());
        assertNull(parse("Portfel: DETAL;").settlementAllowed());
    }

    @Test
    @DisplayName("NIE DOTYCZY w danych o zatrudnieniu traktowane jest jak brak danych")
    void treatsNotApplicableAsMissing() {
        ParsedComment parsed = parse(
                "Miejsce zatrudnienia: NIE DOTYCZY, adres zatrudnienia:NIE DOTYCZY ,tel. do pracy:;");

        assertNull(parsed.employerName());
        assertNull(parsed.employerAddress());
        assertNull(parsed.employerPhone());
        assertFalse(parsed.hasEmployer());
    }

    // ===== obietnice: kolejność kwoty i terminu =====

    @Test
    @DisplayName("Kwota przed terminem")
    void promiseAmountBeforeDate() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 50 PLN 31,12");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(0, new BigDecimal("50").compareTo(promise.amount()));
        assertEquals(LocalDate.of(2016, 12, 31), promise.dueDate());
        assertEquals(ParseConfidence.HIGH, promise.confidence());
    }

    @Test
    @DisplayName("Termin przed kwotą")
    void promiseDateBeforeAmount() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 10,02 100 PLN WIE ZE MALO");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(0, new BigDecimal("100").compareTo(promise.amount()),
                "Waluta rozstrzyga, która liczba jest kwotą");
        assertEquals(LocalDate.of(2017, 2, 10), promise.dueDate());
        assertEquals(ParseConfidence.HIGH, promise.confidence());
    }

    @Test
    @DisplayName("Przyimek DO przed terminem i waluta bez spacji")
    void promiseWithPrepositionAndTightCurrency() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY DO 31,08 1055ZL   WWS");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(0, new BigDecimal("1055").compareTo(promise.amount()));
        assertEquals(LocalDate.of(2016, 8, 31), promise.dueDate());
    }

    @Test
    @DisplayName("Zapis ZŁ i kropka jako separator daty")
    void promiseWithAlternativeCurrencyAndSeparator() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 328 ZŁ 14.04   ODSETKI");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(0, new BigDecimal("328").compareTo(promise.amount()));
        // 14.04 wypada dwa miesiące po dacie eksportu — mieści się w tolerancji,
        // więc czytamy to jako termin bliski, a nie sprzed roku
        assertEquals(LocalDate.of(2017, 4, 14), promise.dueDate());
    }

    @Test
    @DisplayName("Kwota w znacznikach i kwota z groszami")
    void promiseWithMarkersAndDecimals() {
        assertEquals(0, new BigDecimal("50").compareTo(
                parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY #50 PLN#  20,05  D: 700 ZŁ")
                        .promise().amount()));

        assertEquals(0, new BigDecimal("1731.48").compareTo(
                parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 1731,48 ZŁ  09")
                        .promise().amount()));
    }

    @Test
    @DisplayName("Pierwszy termin po kwocie wygrywa z późniejszą datą w notatce")
    void promiseTakesFirstDateAfterAmount() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: "
                + "OBIETNICA SPŁATY  147 PLN  30,12  ZAWIESI DZIALALNOSC OD 31,12");

        assertEquals(LocalDate.of(2016, 12, 30), parsed.promise().dueDate(),
                "30,12 to termin obietnicy; 31,12 dotyczy zawieszenia działalności");
    }

    // ===== obietnice: przypadki niepewne =====

    @Test
    @DisplayName("Wariant ustrukturyzowany odczytywany jest bez heurystyki")
    void readsStructuredPromiseVariant() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: "
                + "OBIETNICA SPŁATY, KWOTA OBIETNICY: 300, DATA OBIETNICY: 2016-06-30");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(ParseConfidence.HIGH, promise.confidence());
        assertEquals(0, new BigDecimal("300").compareTo(promise.amount()));
        assertEquals(LocalDate.of(2016, 6, 30), promise.dueDate(),
                "Rok podany wprost — bez ustalania względem daty eksportu");
    }

    @Test
    @DisplayName("Zaprzeczenie nie tworzy obietnicy")
    void negatedPromiseIsNotAPromise() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: "
                + "OBIETNICY SPŁATY  NIE MA $   PRACUJE NA 0,5 H");

        assertFalse(parsed.hasPromise());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("zaprzeczenia")),
                "Użytkownik musi wiedzieć, dlaczego deklaracji nie utworzono: " + parsed.warnings());
    }

    @Test
    @DisplayName("Obietnica bez czytelnego terminu ma niższy poziom pewności")
    void promiseWithoutDateIsMedium() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY   93,75   PLN   07");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(ParseConfidence.MEDIUM, promise.confidence());
        assertEquals(0, new BigDecimal("93.75").compareTo(promise.amount()));
        assertNull(promise.dueDate());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("bez terminu")));
    }

    @Test
    @DisplayName("Obietnica bez kwoty wymaga sprawdzenia przez człowieka")
    void promiseWithoutAmountIsLow() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY DO   11");

        PromiseOfPayment promise = parsed.promise();
        assertEquals(ParseConfidence.LOW, promise.confidence());
        assertNull(promise.amount());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("bez czytelnej kwoty")));
    }

    @Test
    @DisplayName("Oryginalny zapis jest zachowany do weryfikacji")
    void keepsRawFragment() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 345ZL  02.02");

        assertNotNull(parsed.promise().rawText());
        assertTrue(parsed.promise().rawText().contains("345ZL"));
    }

    // ===== ustalanie roku =====

    @Test
    @DisplayName("Termin wypadający daleko w przyszłości dotyczy roku poprzedniego")
    void inferPreviousYearForFarFutureDate() {
        // Eksport z lutego 2017: obietnica na 31.12 padła w grudniu 2016, nie na koniec 2017
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 100 PLN 31,12");

        assertEquals(LocalDate.of(2016, 12, 31), parsed.promise().dueDate());
    }

    @Test
    @DisplayName("Termin w granicach tolerancji zostaje w roku odniesienia")
    void keepsReferenceYearForNearDate() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 221 PLN 16/01");

        assertEquals(LocalDate.of(2017, 1, 16), parsed.promise().dueDate());
    }

    @Test
    @DisplayName("Rok podany wprost jest respektowany")
    void explicitYearWins() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 500 PLN 12.03.2015");

        assertEquals(LocalDate.of(2015, 3, 12), parsed.promise().dueDate());
    }

    @Test
    @DisplayName("Nieistniejąca data jest pomijana")
    void skipsImpossibleDate() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: OBIETNICA SPŁATY 100 PLN 31,02 potem 15,03");

        assertEquals(LocalDate.of(2017, 3, 15), parsed.promise().dueDate(),
                "31 lutego nie istnieje — parser bierze kolejny sensowny termin");
    }

    // ===== przypadki brzegowe =====

    @Test
    @DisplayName("Pusty komentarz nie wywraca parsera")
    void handlesEmptyComment() {
        for (String input : new String[]{null, "", "   "}) {
            ParsedComment parsed = parse(input);
            assertFalse(parsed.hasPromise());
            assertFalse(parsed.hasLastPayment());
            assertFalse(parsed.warnings().isEmpty());
        }
    }

    @Test
    @DisplayName("Notatka bez obietnicy nie tworzy deklaracji")
    void noPromiseMeansNoDeclaration() {
        ParsedComment parsed = parse("treść ostatniego kontaktu z klientem: BRAK KONTAKTU, ADRESAT NIEZNANY");

        assertFalse(parsed.hasPromise());
    }

    @Test
    @DisplayName("Brak sekcji nie powoduje błędu, pola pozostają puste")
    void missingSectionsLeaveNulls() {
        ParsedComment parsed = parse("Portfel: DETAL ;");

        assertEquals("DETAL", parsed.portfolio());
        assertNull(parsed.caseCategory());
        assertNull(parsed.creditStatus());
        assertNull(parsed.lastPaymentDate());
        assertFalse(parsed.hasPromise());
    }
}
