package com.collectorstools.exception;

/**
 * Naruszenie reguły biznesowej — sytuacja przewidziana, którą użytkownik może naprawić.
 *
 * <p>Komunikat tego wyjątku jest przeznaczony **wprost dla użytkownika** i trafia do okna
 * dialogowego bez zmian. Nie zawiera nazw klas, kodów SQL ani stosu wywołań; mówi, co się
 * stało i co z tym zrobić. Przykład: „Kwota wpłaty (1200,00 zł) przekracza pozostałe saldo
 * sprawy (1000,00 zł). Maksymalna możliwa wpłata to 1000,00 zł."
 *
 * <p>Dziedziczenie po {@link IllegalArgumentException} jest świadome: wszystkie reguły
 * biznesowe w projekcie były wcześniej zgłaszane właśnie tym typem, więc istniejące miejsca
 * przechwytujące wyjątki oraz testy działają bez zmian. Semantycznie też się broni —
 * z punktu widzenia serwisu to argument niespełniający warunków operacji.
 *
 * @see TechnicalException dla awarii, których użytkownik nie naprawi
 */
public class BusinessRuleException extends IllegalArgumentException {

    public BusinessRuleException(String userMessage) {
        super(userMessage);
    }

    public BusinessRuleException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
