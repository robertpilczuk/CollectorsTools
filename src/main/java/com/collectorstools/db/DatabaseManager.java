package com.collectorstools.db;

import com.collectorstools.exception.TechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    /** Nazwa pliku bazy — używana też przy przywracaniu kopii przed startem aplikacji. */
    public static final String DB_FILE_NAME = "collectorstools.db";

    private static final String DB_FILE = DB_FILE_NAME;
    private static final String URL = "jdbc:sqlite:" + DB_FILE;

    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        connect();
        initSchema();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    public Connection getConnection() {
        return connection;
    }

    /** Ścieżka pliku bazy danych (do kopii zapasowej / przywracania). */
    public String getDbPath() {
        return DB_FILE;
    }

    private void connect() {
        try {
            connection = DriverManager.getConnection(URL);
            // Włącz klucze obce w SQLite (domyślnie wyłączone)
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            log.info("Połączono z bazą danych: {}", DB_FILE);
        } catch (SQLException e) {
            throw new TechnicalException(
                    "Nie udało się otworzyć bazy danych \"" + DB_FILE + "\".\n\n"
                            + "Najczęstsze przyczyny: plik jest używany przez inny program, "
                            + "został uszkodzony albo katalog nie pozwala na zapis.\n\n"
                            + "Zamknij pozostałe okna aplikacji i spróbuj ponownie. "
                            + "Jeśli problem się powtarza, przywróć bazę z kopii zapasowej.",
                    e.getMessage(), e);
        }
    }

    /**
     * Doprowadza schemat bazy do najnowszej wersji.
     *
     * <p>Definicje tabel nie są trzymane w tej klasie, tylko w plikach migracji
     * ({@code src/main/resources/db/migrations}) — patrz {@link SchemaManager}.
     * Ten sam zestaw migracji stosują testy, więc schemat testowy nie może
     * rozjechać się z produkcyjnym.
     */
    private void initSchema() {
        new SchemaManager(connection).migrate();
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("Połączenie z bazą zamknięte.");
            }
        } catch (SQLException e) {
            log.warn("Błąd przy zamykaniu połączenia", e);
        }
    }
}