-- V6 — generyczny dziennik audytowy.
--
-- Istniejąca tabela debtor_audit pozostaje bez zmian: jest przywiązana do jednej encji
-- (debtor_id NOT NULL z kluczem obcym) i rejestruje wyłącznie zmiany pól dłużnika.
-- Nie da się jej użyć dla wpłat, spraw czy deklaracji, a to właśnie tam zmiany
-- mają konsekwencje finansowe.
--
-- ŚWIADOMY BRAK KLUCZY OBCYCH: wpis audytowy musi przeżyć usunięcie obiektu, którego
-- dotyczy. Klucz obcy z ON DELETE CASCADE skasowałby ślad po usunięciu dłużnika —
-- czyli dokładnie ten wpis, który jest najbardziej potrzebny. Kolumny entity_id
-- i debtor_id są zwykłymi liczbami, celowo bez więzów.
--
-- debtor_id jest denormalizacją: pozwala pokazać pełną historię dłużnika jednym
-- zapytaniem, bez łączenia audytu wpłat przez sprawy i deklaracje przez dłużnika.
--
-- user_id pozostaje na razie stałą wartością pojedynczego użytkownika lokalnego.
-- Kolumna istnieje od początku, żeby wprowadzenie logowania nie wymagało migracji.

CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TEXT    NOT NULL,
    user_id     TEXT,

    entity_type TEXT    NOT NULL,
    entity_id   INTEGER,
    debtor_id   INTEGER,

    operation   TEXT    NOT NULL,
    field_name  TEXT,
    old_value   TEXT,
    new_value   TEXT,
    reason      TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_entity   ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_debtor   ON audit_log(debtor_id);
CREATE INDEX IF NOT EXISTS idx_audit_occurred ON audit_log(occurred_at);
