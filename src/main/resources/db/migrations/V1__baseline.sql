-- V1 — schemat bazowy aplikacji CollectorsTools.
--
-- Wszystkie tabele tworzone są warunkowo (IF NOT EXISTS), dzięki czemu migracja
-- jest bezpieczna również dla baz utworzonych przed wprowadzeniem wersjonowania:
-- istniejące dane pozostają nietknięte, a wersja schematu zostaje ustawiona na 1.

CREATE TABLE IF NOT EXISTS debtors (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name  TEXT NOT NULL,
    last_name   TEXT NOT NULL,
    pesel       TEXT,
    id_number   TEXT,
    phone       TEXT,
    email       TEXT,
    street      TEXT,
    city        TEXT,
    postal_code TEXT,
    notes       TEXT,
    urgent      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS debts (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id        INTEGER NOT NULL,
    case_number      TEXT NOT NULL,
    creditor         TEXT NOT NULL,
    total_amount     REAL NOT NULL,
    remaining_amount REAL NOT NULL,
    debt_date        TEXT,
    description      TEXT,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS declarations (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id        INTEGER NOT NULL,
    debt_id          INTEGER,
    promised_date    TEXT NOT NULL,
    promised_amount  REAL NOT NULL,
    status           TEXT NOT NULL DEFAULT 'OCZEKUJACA',
    broken_reason    TEXT,
    payment_date     TEXT,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE,
    FOREIGN KEY (debt_id)   REFERENCES debts(id)   ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS interactions (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id        INTEGER NOT NULL,
    interaction_date TEXT NOT NULL,
    contact_type     TEXT NOT NULL,
    notes            TEXT,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS debtor_audit (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id   INTEGER NOT NULL,
    changed_at  TEXT NOT NULL,
    field_name  TEXT NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE
);
