package com.collectorstools.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wykonuje operacje na bazie w jednej transakcji.
 *
 * <p>Bez tego mechanizmu operacja złożona z kilku zapisów może zostać przerwana
 * w połowie i zostawić bazę w stanie niespójnym — na przykład wpłata zapisana
 * w tabeli {@code payments}, ale bez zaktualizowanego salda sprawy. Kod
 * przekazany do {@link #execute} wykonuje się w całości albo wcale.
 *
 * <p>Przykład użycia w serwisie:
 *
 * <pre>{@code
 * transactionManager.execute(() -> {
 *     paymentRepository.save(payment);
 *     debtRepository.updateRemainingAmount(debtId, newBalance);
 * });
 * }</pre>
 *
 * <p><b>Zagnieżdżenia:</b> aplikacja pracuje na jednym współdzielonym połączeniu,
 * więc prawdziwe transakcje zagnieżdżone nie istnieją. Wywołanie {@code execute}
 * wewnątrz trwającej już transakcji nie otwiera nowej — operacja dołącza do
 * bieżącej, a o zatwierdzeniu decyduje wywołanie najbardziej zewnętrzne. Dzięki
 * temu serwis może bezpiecznie wołać inny serwis, który też używa transakcji.
 */
public class TransactionManager {

    private final Connection conn;

    public TransactionManager() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public TransactionManager(Connection conn) {
        this.conn = conn;
    }

    /**
     * Operacja transakcyjna bez wyniku.
     */
    @FunctionalInterface
    public interface Operation {
        void run() throws SQLException;
    }

    /**
     * Operacja transakcyjna zwracająca wynik.
     */
    @FunctionalInterface
    public interface ResultOperation<T> {
        T run() throws SQLException;
    }

    /**
     * Wykonuje operację w transakcji. Błąd na dowolnym kroku wycofuje wszystkie
     * zmiany wykonane w jej ramach.
     */
    public void execute(Operation operation) {
        executeWithResult(() -> {
            operation.run();
            return null;
        });
    }

    /**
     * Jak {@link #execute}, ale zwraca wynik operacji — przydatne, gdy trzeba
     * oddać obiekt zapisany w transakcji (np. wpłatę z nadanym id).
     *
     * <p>Wyjątki biznesowe (nieweryfikowalne) propagują się bez zmiany, żeby
     * warstwa UI mogła pokazać użytkownikowi oryginalny komunikat.
     */
    public <T> T executeWithResult(ResultOperation<T> operation) {
        boolean outermost = startTransaction();

        if (!outermost) {
            // Transakcja już trwa — dołączamy do niej, commit należy do wywołania zewnętrznego
            try {
                return operation.run();
            } catch (SQLException e) {
                throw new RuntimeException("Błąd operacji w transakcji: " + e.getMessage(), e);
            }
        }

        try {
            T result = operation.run();
            conn.commit();
            return result;
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("Transakcja wycofana: " + e.getMessage(), e);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * @return {@code true}, jeśli to wywołanie otworzyło transakcję (jest najbardziej
     *         zewnętrzne); {@code false}, jeśli transakcja już trwała
     */
    private boolean startTransaction() {
        try {
            if (!conn.getAutoCommit()) {
                return false;
            }
            conn.setAutoCommit(false);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Nie udało się rozpocząć transakcji: " + e.getMessage(), e);
        }
    }

    /**
     * Wycofanie nie może przesłonić pierwotnego błędu, który je wywołał —
     * dlatego ewentualny wyjątek trafia tylko na wyjście diagnostyczne.
     */
    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException e) {
            System.err.println("Błąd przy wycofywaniu transakcji: " + e.getMessage());
        }
    }

    private void restoreAutoCommit() {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            System.err.println("Błąd przy przywracaniu autocommit: " + e.getMessage());
        }
    }
}
