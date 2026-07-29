package com.collectorstools.exception;

/**
 * Awaria techniczna — błąd bazy danych, brak pliku, uszkodzony zasób.
 *
 * <p>Użytkownik nie naprawi jej przez zmianę danych w formularzu, więc komunikat dzieli się
 * na dwie części: {@link #getUserMessage()} mówi po ludzku, co się stało i co zrobić dalej,
 * a szczegóły techniczne trafiają do logu.
 *
 * <p>Rozdzielenie ma konkretny cel: pokazanie użytkownikowi komunikatu w rodzaju
 * „SQLITE_BUSY: database is locked" nie daje mu żadnej wskazówki, a jednocześnie ukrywa
 * informację, która jest potrzebna przy diagnozie.
 */
public class TechnicalException extends RuntimeException {

    private final String userMessage;

    public TechnicalException(String userMessage, String technicalDetail, Throwable cause) {
        super(technicalDetail, cause);
        this.userMessage = userMessage;
    }

    public TechnicalException(String userMessage, Throwable cause) {
        this(userMessage, cause != null ? cause.getMessage() : userMessage, cause);
    }

    /**
     * Awaria operacji bazodanowej.
     *
     * <p>Komunikat trzyma się układu: co się nie udało → jaki jest skutek dla danych →
     * co użytkownik może zrobić. Skutek jest tu istotny: dzięki transakcjom nieudana
     * operacja nie zostawia połowicznego zapisu, a użytkownik powinien to wiedzieć,
     * zanim zacznie sprawdzać, czy „coś się zapisało".
     *
     * @param operation opis operacji dopełniający zdanie „Nie udało się …", np. „zapisać wpłaty"
     */
    public static TechnicalException database(String operation, Throwable cause) {
        return new TechnicalException(
                "Nie udało się " + operation + " z powodu błędu bazy danych.\n\n"
                        + "Dane nie zostały zmienione — operacja została wycofana w całości.\n\n"
                        + "Spróbuj ponownie. Jeśli problem się powtarza, zamknij aplikację "
                        + "i przywróć bazę z kopii zapasowej.",
                operation + ": " + (cause != null ? cause.getMessage() : "brak szczegółów"),
                cause);
    }

    /**
     * Komunikat przeznaczony dla użytkownika — bez szczegółów technicznych.
     */
    public String getUserMessage() {
        return userMessage;
    }
}
