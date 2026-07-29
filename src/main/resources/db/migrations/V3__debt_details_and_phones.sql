-- V3 — rozszerzenie sprawy o dane z eksportu wierzyciela, ochrona salda przed
-- zejściem poniżej zera oraz obsługa wielu numerów telefonu dłużnika.
--
-- Tabela debts jest przebudowywana, bo SQLite nie potrafi dodać ograniczenia CHECK
-- przez ALTER TABLE. Skoro przebudowa i tak jest konieczna, wszystkie nowe kolumny
-- wchodzą w tej samej operacji — powtórzenie jej później na bazie produkcyjnej
-- byłoby kosztowne i ryzykowne.
--
-- UWAGA: DROP TABLE przy włączonych kluczach obcych wykonuje niejawne DELETE FROM
-- i uruchamia akcje ON DELETE tabel zależnych (declarations.debt_id ma SET NULL).
-- SchemaManager wyłącza klucze obce na czas migracji i sprawdza spójność
-- przez PRAGMA foreign_key_check przed zatwierdzeniem.
--
-- Rozbicie salda dla istniejących spraw nie jest znane, dlatego całość trafia
-- do kapitału, a odsetki i koszty pozostają zerowe. Niezmiennik
-- "saldo = kapitał + odsetki + koszty" jest dzięki temu spełniony od razu.

CREATE TABLE debts_v3 (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id              INTEGER NOT NULL,

    case_number            TEXT NOT NULL,
    creditor               TEXT NOT NULL,
    original_creditor      TEXT,
    portfolio              TEXT,
    product                TEXT,
    bank_account           TEXT,

    total_amount           REAL NOT NULL,
    remaining_amount       REAL NOT NULL CHECK (remaining_amount >= 0),
    principal_amount       REAL NOT NULL DEFAULT 0,
    interest_amount        REAL NOT NULL DEFAULT 0,
    cost_amount            REAL NOT NULL DEFAULT 0,

    case_category          TEXT,
    credit_status          TEXT,
    settlement_allowed     INTEGER,

    court                  TEXT,
    court_signature        TEXT,

    debt_date              TEXT,
    service_start_date     TEXT,
    enforcement_start_date TEXT,
    description            TEXT,

    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
);

INSERT INTO debts_v3 (
    id, debtor_id, case_number, creditor,
    total_amount, remaining_amount, principal_amount, interest_amount, cost_amount,
    debt_date, description
)
SELECT
    id, debtor_id, case_number, creditor,
    total_amount, remaining_amount, remaining_amount, 0, 0,
    debt_date, description
FROM debts;

DROP TABLE debts;

ALTER TABLE debts_v3 RENAME TO debts;

CREATE INDEX IF NOT EXISTS idx_debts_debtor ON debts(debtor_id);

-- Dłużnik: adres korespondencyjny i dane o zatrudnieniu (istotne przy wizycie terenowej)

ALTER TABLE debtors ADD COLUMN secondary_address TEXT;
ALTER TABLE debtors ADD COLUMN employer_name TEXT;
ALTER TABLE debtors ADD COLUMN employer_address TEXT;
ALTER TABLE debtors ADD COLUMN employer_phone TEXT;

-- Wiele numerów telefonu na dłużnika. Kolumna debtors.phone pozostaje jako numer
-- podstawowy — nie usuwamy jej, żeby nie zmieniać działania eksportu i formularzy.

CREATE TABLE IF NOT EXISTS debtor_phones (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id    INTEGER NOT NULL,
    phone_number TEXT NOT NULL,
    is_primary   INTEGER NOT NULL DEFAULT 0,
    source       TEXT,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_debtor_phones_debtor ON debtor_phones(debtor_id);
CREATE INDEX IF NOT EXISTS idx_debtor_phones_number ON debtor_phones(phone_number);

-- Przeniesienie dotychczasowych numerów do nowej tabeli jako numery podstawowe

INSERT INTO debtor_phones (debtor_id, phone_number, is_primary, source)
SELECT id, trim(phone), 1, 'MIGRACJA'
FROM debtors
WHERE phone IS NOT NULL AND trim(phone) <> '';
