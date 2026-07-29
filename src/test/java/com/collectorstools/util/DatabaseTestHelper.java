package com.collectorstools.util;

import com.collectorstools.db.SchemaManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tworzy izolowane połączenie do bazy in-memory SQLite z pełnym schematem.
 * Każde wywołanie {@link #createTestConnection()} = świeża, pusta baza.
 *
 * <p>Schemat budowany jest przez {@link SchemaManager} — dokładnie tym samym
 * zestawem migracji, którego używa aplikacja produkcyjna. Definicje tabel nie są
 * tu powielane, więc testy nie mogą działać na innym schemacie niż produkcja.
 */
public final class DatabaseTestHelper {

    private DatabaseTestHelper() {
    }

    public static Connection createTestConnection() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            new SchemaManager(conn).migrate();
            return conn;
        } catch (SQLException e) {
            throw new RuntimeException("Nie udało się utworzyć testowej bazy: " + e.getMessage(), e);
        }
    }
}
