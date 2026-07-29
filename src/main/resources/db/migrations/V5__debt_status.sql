-- V5 — status sprawy, data i przyczyna zamknięcia.
--
-- Bez statusu sprawa spłacona była nieodróżnialna od aktywnej, a sprawa umorzona
-- czy przedawniona nie miała w ogóle reprezentacji — pozostawała "aktywna"
-- z dodatnim saldem, zawyżając sumę długów i zaśmiecając listę zadań windykatora.
--
-- ALTER TABLE wystarcza: SQLite pozwala dodać kolumnę NOT NULL, o ile ma wartość
-- domyślną. Przebudowa tabeli nie jest tu potrzebna.

ALTER TABLE debts ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE debts ADD COLUMN closed_at TEXT;
ALTER TABLE debts ADD COLUMN close_reason TEXT;

-- Sprawy z zerowym saldem są z definicji spłacone. Daty zamknięcia nie zmyślamy —
-- nie była nigdzie zapisywana, więc pozostaje pusta.

UPDATE debts SET status = 'CLOSED_PAID' WHERE remaining_amount = 0;

CREATE INDEX IF NOT EXISTS idx_debts_status ON debts(status);
