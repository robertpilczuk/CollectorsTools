package com.collectorstools.importer;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rozkłada kolumnę „Komentarz do sprawy" z eksportu wierzyciela na pola.
 *
 * <p>Pole składa się z dwóch warstw. Sekcje z etykietami (portfel, kategoria sprawy,
 * status kredytu, zgoda na ugodę, zatrudnienie, ostatnia wpłata, data wszczęcia egzekucji)
 * mają stały format i odczytują się pewnie. Ostatnia sekcja — treść kontaktu z klientem —
 * to swobodna notatka pisana w pośpiechu, w której obietnica spłaty pojawia się
 * w kilkunastu wariantach zapisu.
 *
 * <p><b>Zasada:</b> parser nie zgaduje po cichu. Odczyt niepewny dostaje niższy poziom
 * {@link ParseConfidence} i zastrzeżenie w {@code warnings}, żeby człowiek zobaczył,
 * czego maszyna nie była pewna, zamiast dostać wymyśloną datę jako fakt.
 *
 * <p>Warianty zapisu obietnicy spotkane w rzeczywistym portfelu (171 spraw):
 * <pre>
 *   OBIETNICA SPŁATY 50 PLN 31,12          kwota, potem termin
 *   OBIETNICA SPŁATY 10,02 100 PLN         termin, potem kwota
 *   OBIETNICA SPŁATY DO 31,08 1055ZL       przyimek, brak spacji przed walutą
 *   OBIETNICA SPŁATY 328 ZŁ 14.04          inna waluta i separator daty
 *   OBIETNICA SPŁATY #900 PLN# DO 12...     kwota w znacznikach
 *   OBIETNICY SPŁATY NIE MA                 zaprzeczenie — brak obietnicy
 * </pre>
 */
public class CaseCommentParser {

    /**
     * Ile miesięcy w przód od daty odniesienia dopuszczamy, zanim uznamy,
     * że termin bez roku dotyczy roku poprzedniego.
     */
    private static final int FUTURE_TOLERANCE_MONTHS = 3;

    private static final Pattern PORTFOLIO = section("Portfel");
    private static final Pattern CATEGORY = section("kategoria sprawy");
    private static final Pattern CREDIT_STATUS = section("status kredytu");
    private static final Pattern CREDIT_AMOUNT = section("kwota kredytu");
    private static final Pattern SETTLEMENT = Pattern.compile(
            "zezwolenie na zawarcie ugody:\\s*(TAK|NIE)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTE = section("treść notatki");

    private static final Pattern EMPLOYER_NAME = Pattern.compile(
            "Miejsce zatrudnienia:\\s*(.*?)\\s*,\\s*adres zatrudnienia:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPLOYER_ADDRESS = Pattern.compile(
            "adres zatrudnienia:\\s*(.*?)\\s*,\\s*tel\\. do pracy:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPLOYER_PHONE = section("tel\\. do pracy");

    private static final Pattern LAST_PAYMENT = Pattern.compile(
            "data i kwota ostatniej wpłaty:\\s*(\\d{4}-\\d{2}-\\d{2})\\s+([\\d ]+(?:[.,]\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENFORCEMENT = Pattern.compile(
            "Data wszczęcia egzekucji:\\s*(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_CONTACT = Pattern.compile(
            "treść ostatniego kontaktu z klientem:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Fragment notatki od słowa OBIETNIC… dalej.
     *
     * <p>Ogranicznikiem jest wyłącznie średnik. Ukośnik, choć używany w notatkach jako
     * separator myśli, występuje też w terminach zapisanych jako {@code 16/01} — obcięcie
     * fragmentu na nim zabierałoby datom miesiąc.
     */
    private static final Pattern PROMISE_FRAGMENT = Pattern.compile(
            "OBIETNIC\\w*\\s+SPŁAT\\w*([^;]{0,120})", Pattern.CASE_INSENSITIVE);

    /**
     * Kwota rozpoznawana po jednostce waluty — to ona odróżnia kwotę od daty.
     *
     * <p>Świadomie bez obsługi spacji jako separatora tysięcy: w zapisie
     * {@code OBIETNICA SPŁATY 10,02 100 PLN} wzorzec dopuszczający spację potrafił
     * skleić fragment daty z kwotą i odczytać 2100 zamiast 100. W badanym portfelu
     * kwoty z separatorem tysięcy nie wystąpiły ani razu.
     */
    private static final Pattern AMOUNT_WITH_CURRENCY = Pattern.compile(
            "(\\d+(?:[.,]\\d{1,2})?)\\s*#?\\s*(?:PLN|ZŁ|ZL)", Pattern.CASE_INSENSITIVE);

    /** Termin w postaci dzień-miesiąc, opcjonalnie z rokiem. */
    private static final Pattern DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})[.,/](\\d{1,2})(?:[.,/](\\d{2,4}))?\\b");

    /**
     * Wariant w pełni ustrukturyzowany, spotykany sporadycznie:
     * {@code KWOTA OBIETNICY: 300, DATA OBIETNICY: 2016-06-30}.
     * Sprawdzany przed heurystyką, bo nie wymaga zgadywania.
     */
    private static final Pattern STRUCTURED_PROMISE = Pattern.compile(
            "KWOTA OBIETNICY:\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*,?\\s*"
                    + "DATA OBIETNICY:\\s*(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);

    /** Zwroty, po których „obietnica" oznacza jej brak. */
    private static final String[] NEGATIONS = {
            "NIE MA", "NIE DOKONA", "NIE ZAPŁACI", "NIE ZAPLACI",
            "BRAK OBIETNIC", "NIE OBIECUJE", "NIE SKŁADA", "NIE SKLADA"
    };

    private static final String NOT_APPLICABLE = "NIE DOTYCZY";

    private static Pattern section(String label) {
        return Pattern.compile(label + ":\\s*([^;]*)", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Rozkłada komentarz na pola.
     *
     * @param comment       zawartość kolumny; {@code null} i pusty tekst są dozwolone
     * @param referenceDate data, względem której ustalany jest rok w terminach zapisanych
     *                      bez roku — zwykle data eksportu pliku
     */
    public ParsedComment parse(String comment, LocalDate referenceDate) {
        List<String> warnings = new ArrayList<>();

        if (comment == null || comment.isBlank()) {
            warnings.add("Pusty komentarz — brak danych do odczytania.");
            return new ParsedComment(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, warnings);
        }

        String portfolio = value(PORTFOLIO, comment);
        String category = value(CATEGORY, comment);
        String creditStatus = value(CREDIT_STATUS, comment);
        Boolean settlement = settlementAllowed(comment);
        BigDecimal creditAmount = amount(value(CREDIT_AMOUNT, comment));

        String employerName = notApplicableToNull(value(EMPLOYER_NAME, comment));
        String employerAddress = notApplicableToNull(value(EMPLOYER_ADDRESS, comment));
        String employerPhone = notApplicableToNull(value(EMPLOYER_PHONE, comment));

        LocalDate lastPaymentDate = null;
        BigDecimal lastPaymentAmount = null;
        Matcher payment = LAST_PAYMENT.matcher(comment);
        if (payment.find()) {
            lastPaymentDate = isoDate(payment.group(1), warnings, "data ostatniej wpłaty");
            lastPaymentAmount = amount(payment.group(2));
            if (lastPaymentAmount == null) {
                warnings.add("Nie udało się odczytać kwoty ostatniej wpłaty.");
            }
        }

        LocalDate enforcementDate = null;
        Matcher enforcement = ENFORCEMENT.matcher(comment);
        if (enforcement.find()) {
            enforcementDate = isoDate(enforcement.group(1), warnings, "data wszczęcia egzekucji");
        }

        String note = value(NOTE, comment);
        String lastContact = value(LAST_CONTACT, comment);

        PromiseOfPayment promise = parsePromise(
                lastContact != null ? lastContact : comment, referenceDate, warnings);

        return new ParsedComment(portfolio, category, creditStatus, settlement, creditAmount,
                employerName, employerAddress, employerPhone,
                lastPaymentDate, lastPaymentAmount, enforcementDate,
                note, lastContact, promise, warnings);
    }

    /**
     * Wyciąga obietnicę spłaty ze swobodnej notatki.
     *
     * <p>Kolejność kroków wynika z tego, że kwota i termin bywają zapisane w obu
     * kolejnościach, a zapis {@code 31,12} może oznaczać zarówno kwotę, jak i datę.
     * Jednostka waluty jest jedynym pewnym rozróżnieniem, dlatego najpierw szukamy
     * kwoty przy walucie, a terminu dopiero w tym, co zostanie.
     */
    private PromiseOfPayment parsePromise(String text, LocalDate referenceDate, List<String> warnings) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // Zapis ustrukturyzowany nie wymaga heurystyki — obsługujemy go w pierwszej kolejności
        Matcher structured = STRUCTURED_PROMISE.matcher(text);
        if (structured.find()) {
            BigDecimal structuredAmount = amount(structured.group(1));
            LocalDate structuredDate = isoDate(structured.group(2), warnings, "data obietnicy");
            if (structuredAmount != null && structuredDate != null) {
                return new PromiseOfPayment(structuredAmount, structuredDate,
                        ParseConfidence.HIGH, structured.group());
            }
        }

        Matcher fragmentMatcher = PROMISE_FRAGMENT.matcher(text);
        if (!fragmentMatcher.find()) {
            return null;
        }
        String fragment = fragmentMatcher.group(1).strip();
        String upper = fragment.toUpperCase();

        for (String negation : NEGATIONS) {
            if (upper.contains(negation)) {
                warnings.add("Notatka wspomina o obietnicy, ale w formie zaprzeczenia — "
                        + "deklaracji nie utworzono.");
                return null;
            }
        }

        Matcher amountMatcher = AMOUNT_WITH_CURRENCY.matcher(fragment);
        if (!amountMatcher.find()) {
            warnings.add("W notatce jest mowa o obietnicy spłaty, ale bez czytelnej kwoty: \""
                    + shorten(fragment) + "\"");
            return new PromiseOfPayment(null, null, ParseConfidence.LOW, fragment);
        }

        BigDecimal promisedAmount = amount(amountMatcher.group(1));
        if (promisedAmount == null || promisedAmount.signum() <= 0) {
            warnings.add("Kwota obietnicy nieczytelna: \"" + shorten(fragment) + "\"");
            return new PromiseOfPayment(null, null, ParseConfidence.LOW, fragment);
        }

        // Kwota zostaje wycięta, żeby jej cyfry nie zostały wzięte za termin
        String withoutAmount = fragment.substring(0, amountMatcher.start())
                + " " + fragment.substring(amountMatcher.end());

        LocalDate dueDate = findDueDate(withoutAmount, referenceDate);
        if (dueDate == null) {
            warnings.add("Odczytano kwotę obietnicy (" + promisedAmount.toPlainString()
                    + "), ale bez terminu: \"" + shorten(fragment) + "\"");
            return new PromiseOfPayment(promisedAmount, null, ParseConfidence.MEDIUM, fragment);
        }
        return new PromiseOfPayment(promisedAmount, dueDate, ParseConfidence.HIGH, fragment);
    }

    /**
     * Pierwszy sensowny termin w tekście. Rok, jeśli go nie podano, ustalany jest
     * względem daty odniesienia: termin wypadający wyraźnie w przyszłości dotyczy
     * roku poprzedniego, bo obietnica została złożona przed sporządzeniem eksportu.
     */
    private LocalDate findDueDate(String text, LocalDate referenceDate) {
        Matcher matcher = DAY_MONTH.matcher(text);
        while (matcher.find()) {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            Integer year = matcher.group(3) != null ? normaliseYear(matcher.group(3)) : null;

            if (day < 1 || day > 31 || month < 1 || month > 12) {
                continue;
            }
            try {
                if (year != null) {
                    return LocalDate.of(year, month, day);
                }
                LocalDate candidate = LocalDate.of(referenceDate.getYear(), month, day);
                if (candidate.isAfter(referenceDate.plusMonths(FUTURE_TOLERANCE_MONTHS))) {
                    candidate = candidate.minusYears(1);
                }
                return candidate;
            } catch (DateTimeException e) {
                // np. 31.02 — nieistniejąca data, szukamy dalej
            }
        }
        return null;
    }

    private static int normaliseYear(String raw) {
        int year = Integer.parseInt(raw);
        return year < 100 ? 2000 + year : year;
    }

    private Boolean settlementAllowed(String comment) {
        Matcher matcher = SETTLEMENT.matcher(comment);
        if (!matcher.find()) {
            return null;
        }
        return "TAK".equalsIgnoreCase(matcher.group(1).strip());
    }

    private String value(Pattern pattern, String comment) {
        Matcher matcher = pattern.matcher(comment);
        if (!matcher.find()) {
            return null;
        }
        String found = matcher.group(1).strip();
        return found.isEmpty() ? null : found;
    }

    private static String notApplicableToNull(String value) {
        return value == null || NOT_APPLICABLE.equalsIgnoreCase(value) ? null : value;
    }

    private LocalDate isoDate(String raw, List<String> warnings, String label) {
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            warnings.add("Nieprawidłowa " + label + ": \"" + raw + "\"");
            return null;
        }
    }

    /**
     * Kwoty w eksporcie używają przecinka dziesiętnego i spacji jako separatora tysięcy.
     */
    private static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shorten(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 60 ? single.substring(0, 60) + "…" : single;
    }
}
