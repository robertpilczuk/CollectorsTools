package com.collectorstools.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Zarządza schematem bazy danych.
 *
 * <p>Pliki migracji w {@code src/main/resources/db/migrations} są <b>jedynym</b>
 * źródłem prawdy o schemacie — korzysta z nich zarówno aplikacja
 * ({@link DatabaseManager}), jak i testy ({@code DatabaseTestHelper}). Dzięki temu
 * schemat produkcyjny i testowy nie mogą się rozjechać.
 *
 * <p>Wersja schematu przechowywana jest w samym pliku bazy przez
 * {@code PRAGMA user_version}, więc nie wymaga dodatkowej tabeli. Wersja N oznacza,
 * że zastosowano N pierwszych migracji z listy {@link #MIGRATIONS}.
 *
 * <p>Każda migracja wykonuje się w transakcji — błąd w dowolnej instrukcji cofa
 * całą migrację i pozostawia bazę w poprzedniej, spójnej wersji.
 *
 * <p><b>Dodanie nowej migracji:</b> utwórz plik {@code V{n}__opis.sql}
 * w katalogu migracji i dopisz jego nazwę na końcu {@link #MIGRATIONS}.
 * Nigdy nie modyfikuj migracji już wydanej — dodaj kolejną.
 */
public class SchemaManager {

    private static final String MIGRATIONS_PATH = "/db/migrations/";

    /**
     * Kolejność ma znaczenie: pozycja i-ta odpowiada wersji schematu i+1.
     */
    private static final String[] MIGRATIONS = {
            "V1__baseline.sql",
            "V2__declaration_statuses.sql",
            "V3__debt_details_and_phones.sql",
            "V4__payments.sql",
            "V5__debt_status.sql",
            "V6__audit_log.sql"
    };

    private final Connection conn;

    public SchemaManager(Connection conn) {
        this.conn = conn;
    }

    /**
     * Doprowadza bazę do najnowszej wersji schematu, stosując brakujące migracje
     * po kolei. Wywołanie na bazie już aktualnej nic nie zmienia.
     */
    public void migrate() {
        try {
            applyLegacyFixes();

            int version = currentVersion();
            if (version >= MIGRATIONS.length) {
                return;
            }

            // Klucze obce muszą być wyłączone na czas migracji. Migracje przebudowujące
            // tabelę (nowa tabela → przepisanie danych → DROP starej → RENAME) natrafiają
            // na zachowanie SQLite, w którym DROP TABLE wykonuje niejawne DELETE FROM
            // i URUCHAMIA akcje ON DELETE tabel zależnych. Przy włączonych kluczach
            // usunięcie tabeli debts wyzerowałoby debt_id we wszystkich deklaracjach.
            //
            // PRAGMA foreign_keys jest ignorowana wewnątrz transakcji, więc przełączenie
            // musi nastąpić przed jej rozpoczęciem. Spójność sprawdzamy przed commitem
            // przez PRAGMA foreign_key_check.
            boolean foreignKeysWereOn = foreignKeysEnabled();
            if (foreignKeysWereOn) {
                setForeignKeys(false);
            }
            try {
                for (int i = version; i < MIGRATIONS.length; i++) {
                    applyMigration(MIGRATIONS[i], i + 1);
                }
            } finally {
                if (foreignKeysWereOn) {
                    setForeignKeys(true);
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Błąd migracji schematu bazy: " + e.getMessage(), e);
        }
    }

    private boolean foreignKeysEnabled() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private void setForeignKeys(boolean enabled) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = " + (enabled ? "ON" : "OFF"));
        }
    }

    /**
     * Weryfikuje, czy migracja nie zostawiła osieroconych powiązań. Wywoływane
     * przed commitem — naruszenie cofa całą migrację.
     */
    private void verifyForeignKeys(String fileName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new SQLException("Migracja " + fileName
                        + " naruszyła spójność kluczy obcych (tabela: " + rs.getString(1) + ")");
            }
        }
    }

    /**
     * Aktualna wersja schematu — 0 dla bazy, na której nie zastosowano migracji.
     */
    public int currentVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Najwyższa dostępna wersja schematu.
     */
    public static int latestVersion() {
        return MIGRATIONS.length;
    }

    private void applyMigration(String fileName, int targetVersion) throws SQLException, IOException {
        List<String> statements = readStatements(MIGRATIONS_PATH + fileName);

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
            verifyForeignKeys(fileName);
            // user_version jest częścią nagłówka pliku bazy — zmiana jest transakcyjna
            st.execute("PRAGMA user_version = " + targetVersion);
            conn.commit();
            System.out.println("Zastosowano migrację " + fileName + " (wersja schematu: " + targetVersion + ")");
        } catch (SQLException e) {
            conn.rollback();
            throw new SQLException("Migracja " + fileName + " nie powiodła się: " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Poprawki dla baz utworzonych przed wprowadzeniem wersjonowania.
     *
     * <p>Migracja V1 tworzy tabele warunkowo, więc nie doda kolumny do tabeli,
     * która już istnieje w starszej postaci. Kolumna {@code urgent} pojawiła się
     * po pierwszym wydaniu i wymaga osobnego dołożenia.
     *
     * <p>Na świeżej bazie metoda nic nie robi — tabel jeszcze nie ma.
     */
    private void applyLegacyFixes() throws SQLException {
        if (!tableExists("debtors")) {
            return;
        }
        addColumnIfMissing("debtors", "urgent", "INTEGER NOT NULL DEFAULT 0");
    }

    private boolean tableExists(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    /**
     * Dodaje kolumnę do istniejącej tabeli, jeśli jeszcze nie istnieje.
     * SQLite nie zna składni ADD COLUMN IF NOT EXISTS.
     */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    /**
     * Czyta plik migracji i dzieli go na pojedyncze instrukcje SQL.
     * Pomija linie komentarza ({@code --}) i puste instrukcje.
     */
    private List<String> readStatements(String resource) throws IOException {
        try (InputStream in = SchemaManager.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Nie znaleziono pliku migracji: " + resource);
            }

            StringBuilder sql = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                        continue;
                    }
                    sql.append(line).append('\n');
                }
            }

            List<String> statements = new ArrayList<>();
            for (String candidate : sql.toString().split(";")) {
                String statement = candidate.strip();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
            }
            return statements;
        }
    }
}
