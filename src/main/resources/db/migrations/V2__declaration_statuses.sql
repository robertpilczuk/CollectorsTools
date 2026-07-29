-- V2 — angielskie statusy deklaracji, stany częściowy i anulowany,
-- kolumny created_at oraz cancelled_reason.
--
-- SQLite nie potrafi zmienić wartości DEFAULT ani dodać kolumny NOT NULL bez
-- wartości domyślnej, dlatego tabela jest przebudowywana: nowa struktura,
-- przepisanie danych z mapowaniem statusów, podmiana nazwy.
--
-- created_at dla istniejących deklaracji przyjmuje wartość promised_date —
-- faktyczna data utworzenia nie była wcześniej zapisywana, a data obietnicy
-- jest najbliższym dostępnym przybliżeniem i nie łamie reguły
-- "termin nie wcześniejszy niż data utworzenia".

CREATE TABLE declarations_v2 (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    debtor_id        INTEGER NOT NULL,
    debt_id          INTEGER,
    promised_date    TEXT NOT NULL,
    promised_amount  REAL NOT NULL,
    status           TEXT NOT NULL DEFAULT 'PENDING',
    broken_reason    TEXT,
    cancelled_reason TEXT,
    payment_date     TEXT,
    created_at       TEXT NOT NULL,
    FOREIGN KEY (debtor_id) REFERENCES debtors(id) ON DELETE CASCADE,
    FOREIGN KEY (debt_id)   REFERENCES debts(id)   ON DELETE SET NULL
);

INSERT INTO declarations_v2 (
    id, debtor_id, debt_id, promised_date, promised_amount,
    status, broken_reason, cancelled_reason, payment_date, created_at
)
SELECT
    id,
    debtor_id,
    debt_id,
    promised_date,
    promised_amount,
    CASE status
        WHEN 'OCZEKUJACA' THEN 'PENDING'
        WHEN 'DOTRZYMANA' THEN 'FULFILLED'
        WHEN 'ZLAMANA'    THEN 'BROKEN'
        ELSE status
    END,
    broken_reason,
    NULL,
    payment_date,
    promised_date
FROM declarations;

DROP TABLE declarations;

ALTER TABLE declarations_v2 RENAME TO declarations;
