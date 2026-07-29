-- V4 — ewidencja wpłat.
--
-- Do tej pory aplikacja znała wyłącznie kwotę OBIECANĄ (deklaracje). Kwota
-- faktycznie wpłacona nie była nigdzie zapisywana, a saldo sprawy pozostawało
-- niezmienione po oznaczeniu deklaracji jako dotrzymanej — statystyka "Suma długów"
-- była trwale zawyżona.
--
-- Wpłata zawsze wskazuje sprawę (saldo jest atrybutem sprawy). Powiązanie
-- z deklaracją jest opcjonalne: dłużnik może wpłacić bez wcześniejszej obietnicy.
--
-- Kolumny allocated_* zapisują, ile z wpłaty poszło na koszty, odsetki i kapitał.
-- Dzięki temu korekta błędnie wprowadzonej wpłaty odtwarza rozbicie salda dokładnie
-- tak, jak było przed nią — bez zgadywania.
--
-- user_id jest nullowalne i na razie niewypełniane. Kolumna wchodzi już teraz,
-- żeby wprowadzenie logowania w kolejnym etapie nie wymagało migracji danych.

CREATE TABLE IF NOT EXISTS payments (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    debt_id              INTEGER NOT NULL,
    declaration_id       INTEGER,

    amount               REAL NOT NULL CHECK (amount > 0),
    payment_date         TEXT NOT NULL,

    allocated_cost       REAL NOT NULL DEFAULT 0,
    allocated_interest   REAL NOT NULL DEFAULT 0,
    allocated_principal  REAL NOT NULL DEFAULT 0,

    note                 TEXT,
    source               TEXT NOT NULL DEFAULT 'RECZNIE',
    created_at           TEXT NOT NULL,
    user_id              TEXT,

    FOREIGN KEY (debt_id)        REFERENCES debts(id)        ON DELETE CASCADE,
    FOREIGN KEY (declaration_id) REFERENCES declarations(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_payments_debt        ON payments(debt_id);
CREATE INDEX IF NOT EXISTS idx_payments_declaration ON payments(declaration_id);
CREATE INDEX IF NOT EXISTS idx_payments_date        ON payments(payment_date);
