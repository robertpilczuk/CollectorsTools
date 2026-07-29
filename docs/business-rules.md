# Katalog reguł biznesowych — CollectorsTools

Dokument opisuje reguły, których aplikacja pilnuje w warstwie serwisów i bazy danych.
Każda reguła ma uzasadnienie biznesowe, miejsce egzekwowania i komunikat, jaki widzi
użytkownik. Interfejs może dodatkowo blokować przycisk zapisu dla wygody, ale nigdy
nie jest jedynym miejscem walidacji — inaczej import danych ominąłby regułę.

Proces, którego dotyczą reguły:

```
kontakt → deklaracja → termin → weryfikacja → konsekwencja
```

---

## Dłużnik

### BR-01 — Imię i nazwisko są wymagane

**Gdzie:** `DebtorService.validate`
**Dlaczego:** dłużnik bez nazwiska jest nieidentyfikowalny w kontakcie z wierzycielem.
**Komunikat:** „Imię jest wymagane." / „Nazwisko jest wymagane."

### BR-02 — PESEL, jeśli podany, musi być poprawny

**Gdzie:** `DebtorService.validate` → `PeselValidator`
**Zakres sprawdzenia:** 11 cyfr, poprawna data urodzenia (stulecia 1800–2200), suma kontrolna o wagach `1,3,7,9,…`
**Dlaczego:** PESEL jest kluczem dopasowania sprawy do systemu wierzyciela. Błędny numer uniemożliwia rozliczenie zlecenia.
**Uwaga:** PESEL jest **opcjonalny** — 7 na 171 spraw w rzeczywistym portfelu go nie miało. Reguła dotyczy wyłącznie numerów podanych.
**Komunikat:** „Nieprawidłowy PESEL (długość, cyfry lub suma kontrolna)."

### BR-03 — Powtórzony PESEL oznacza kolejną sprawę tego samego dłużnika

**Gdzie:** import danych
**Dlaczego:** w analizowanym portfelu 148 unikalnych numerów przypadało na 164 sprawy — 16 osób miało po kilka zobowiązań. Traktowanie powtórzenia jako błędu odrzuciłoby poprawne dane.

---

## Sprawa

### BR-10 — Numer sprawy i wierzyciel są wymagane

**Gdzie:** `DebtService.validate`
**Dlaczego:** bez numeru sprawy nie da się rozliczyć zlecenia u wierzyciela.

### BR-11 — Kwota długu musi być dodatnia, saldo nie może być ujemne

**Gdzie:** `DebtService.validate` **oraz** `CHECK (remaining_amount >= 0)` w schemacie
**Dlaczego:** ujemne saldo nie ma sensu i zaburza sumy portfela. Reguła jest zdublowana w bazie, bo utrata pieniędzy przez błąd w kodzie jest kosztowniejsza niż duplikat walidacji.

### BR-12 — Saldo równa się sumie kapitału, odsetek i kosztów

**Gdzie:** `DebtService.validateComponentsMatchBalance`
**Tolerancja:** 1 grosz — kwoty przychodzą z arkuszy jako liczby zmiennoprzecinkowe.
**Dlaczego:** niezmiennik potwierdzony na rzeczywistym eksporcie wierzyciela — zgadzał się co do grosza we wszystkich 171 sprawach. Rozjazd oznacza błąd importu albo ręcznej korekty.
**Wyjątek:** sprawa bez znanego rozbicia salda (suma składników zerowa) nie podlega regule — windykator wpisujący sprawę ręcznie zna często wyłącznie kwotę łączną.
**Komunikat:** „Rozbicie salda nie zgadza się z kwotą pozostałą: 100 + 100 + 0 = 200, oczekiwano 377,69."

### BR-13 — Zamknięcie sprawy z niespłaconym saldem wymaga przyczyny

**Gdzie:** `DebtService.closeCase`
**Dlaczego:** umorzenie, przedawnienie czy skierowanie na drogę sądową to decyzje, za które ktoś odpowiada. Bez wymogu uzasadnienia najprostszym sposobem poprawienia statystyk byłoby zamykanie spraw trudnych.
**Komunikat:** „Zamknięcie sprawy z niespłaconym saldem wymaga podania przyczyny."

### BR-14 — Jako spłaconą można zamknąć wyłącznie sprawę z saldem zerowym

**Gdzie:** `DebtService.closeCase`
**Dlaczego:** status `CLOSED_PAID` jest podstawą wskaźnika odzysku. Dopuszczenie go przy dodatnim saldzie czyniłoby ten wskaźnik bezwartościowym.
**Komunikat:** „Sprawy z niespłaconym saldem nie można zamknąć jako spłaconej. Wybierz inną przyczynę zamknięcia."

### BR-15 — Spłata całości zamyka sprawę automatycznie

**Gdzie:** `PaymentService.closeIfFullyRepaid`
**Dlaczego:** zerowe saldo nie pozostawia miejsca na decyzję — sprawa jest spłacona i nie powinna dłużej figurować na liście zadań ani w sumie długów.
**Ograniczenie:** automat dotyczy wyłącznie spraw aktywnych. Zamknięcia ustawionego ręcznie (umorzenie, droga sądowa) wpłata nie nadpisuje.

### BR-16 — Sprawy z zarejestrowanymi wpłatami nie można usunąć

**Gdzie:** `DebtService.delete` → `DebtRepository.hasPayments`
**Dlaczego:** usunięcie zabrałoby historię finansową, której nie da się odtworzyć. Sprawę zakończoną się zamyka, a nie kasuje.
**Komunikat:** „Nie można usunąć sprawy, do której zarejestrowano wpłaty. Zamknij ją zamiast usuwać — historia wpłat musi zostać zachowana."

---

## Deklaracja

### BR-20 — Deklaracja wymaga dłużnika, daty i kwoty dodatniej

**Gdzie:** `DeclarationService.validate`

### BR-21 — Termin nie może być wcześniejszy niż data zarejestrowania

**Gdzie:** `DeclarationService.validate`
**Dlaczego:** obietnica z terminem sprzed momentu jej złożenia jest sprzeczna sama w sobie.
**Jak rejestrować wstecz:** deklarację z wizyty sprzed kilku dni albo z importu danych historycznych zapisuje się razem z faktyczną datą utworzenia. Reguła blokuje niespójność, nie backdating.
**Komunikat:** „Termin deklaracji (2026-07-01) nie może być wcześniejszy niż data jej zarejestrowania (2026-07-29)."

### BR-22 — Powiązanie ze sprawą jest opcjonalne

**Gdzie:** model `Declaration`
**Dlaczego:** dłużnik w terenie mówi „oddam 500 zł", niekoniecznie precyzując, której sprawy dotyczy obietnica.

### BR-23 — Deklaracja przeterminowana zostaje złamana automatycznie

**Gdzie:** `DeclarationRepository.refreshOverdue`, wywoływane przy starcie aplikacji
**Warunek:** `status = PENDING` **oraz** `promised_date < dziś` — warunek ostry, więc **deklaracja z terminem na dziś ma cały dzień na wpłatę**.
**Czego nie dotyczy:** deklaracji częściowo opłaconych (dłużnik już zapłacił część, o niedotrzymaniu decyduje windykator), anulowanych i rozliczonych.
**Ślad:** złamanie automatyczne nie ma powodu — pusty `broken_reason` przy złamanej deklaracji oznacza „termin po prostu minął, nikt tego nie obsłużył".

### BR-24 — Złamanie ręczne wymaga powodu

**Gdzie:** `DebtorDetailWindow.markBroken` — anulowanie dialogu przerywa operację
**Dlaczego:** powód złamania decyduje później o tym, czy sprawa idzie do sądu, czy do ponownej negocjacji.

### BR-25 — Anulowanie wymaga uzasadnienia

**Gdzie:** `DeclarationService.markAsCancelled`
**Dlaczego:** anulowana deklaracja wypada ze wskaźnika skuteczności. Bez wymogu uzasadnienia byłaby to furtka do poprawiania sobie statystyk.
**Komunikat:** „Anulowanie deklaracji wymaga podania uzasadnienia."

### BR-26 — Deklaracji z wpłatą nie można anulować

**Gdzie:** `DeclarationService.markAsCancelled`
**Dlaczego:** deklaracja z zarejestrowaną wpłatą ma zawsze status `FULFILLED` albo `PARTIALLY_FULFILLED` — reguła wynika z tego wprost i nie wymaga osobnego sprawdzania wpłat.
**Komunikat:** „Nie można anulować deklaracji, do której zarejestrowano wpłatę."

### BR-27 — Do deklaracji anulowanej nie można zarejestrować wpłaty

**Gdzie:** `PaymentService.loadDeclarationIfLinked`, `DeclarationService.markAsFulfilled`

### BR-28 — Wpłata po terminie domyka deklarację złamaną

**Gdzie:** `PaymentService.refreshDeclarationStatus`
**Dlaczego:** wpłata z jednodniowym opóźnieniem jest w windykacji zjawiskiem codziennym. Przed wprowadzeniem tej reguły jedynym wyjściem było usunięcie deklaracji, czyli zniszczenie historii.
**Ślad:** powód złamania **zostaje zachowany**, a interfejs pokazuje „Dotrzymana (po terminie)" na podstawie porównania daty wpłaty z terminem.

---

## Wpłata

### BR-30 — Wpłata zawsze wskazuje sprawę

**Gdzie:** `PaymentService.validate`, `payments.debt_id NOT NULL`
**Dlaczego:** saldo jest atrybutem sprawy — wpłata bez wskazanej sprawy nie miałaby czego pomniejszyć.

### BR-31 — Kwota wpłaty musi być dodatnia

**Gdzie:** `PaymentService.validate` **oraz** `CHECK (amount > 0)` w schemacie

### BR-32 — Wpłata nie może przekroczyć salda sprawy

**Gdzie:** `PaymentService.registerPayment`
**Dlaczego:** nadpłata jest niemal zawsze literówką. Odrzucenie z podaniem maksimum jest jednoznaczne, a obsługa nadpłat nie ma w tej domenie dobrej odpowiedzi biznesowej.
**Komunikat:** „Kwota wpłaty (1200,00 zł) przekracza pozostałe saldo sprawy (1000,00 zł). Maksymalna możliwa wpłata to 1000,00 zł."

### BR-33 — Wpłata zaliczana jest na koszty, potem odsetki, na końcu kapitał

**Gdzie:** `PaymentService.allocate`
**Podstawa:** art. 451 § 1 Kodeksu cywilnego — wierzyciel zalicza wpłatę najpierw na należności uboczne.
**Ślad:** podział zapisywany jest przy wpłacie (`allocated_cost`, `allocated_interest`, `allocated_principal`), dzięki czemu wycofanie wpłaty odtwarza saldo dokładnie, bez zgadywania.
**Wyjątek:** sprawy bez znanego rozbicia salda zmniejszają wyłącznie saldo łączne.

### BR-34 — Zapis wpłaty i aktualizacja salda są niepodzielne

**Gdzie:** `PaymentService.registerPayment` w `TransactionManager`
**Dlaczego:** przerwanie w połowie zostawiłoby wpłatę bez zaktualizowanego salda albo saldo obniżone bez śladu, skąd wzięła się zmiana. Odrzucona wpłata nie zostawia żadnego śladu w ewidencji.

### BR-35 — Status deklaracji wynika z faktycznych wpłat

**Gdzie:** `PaymentService.refreshDeclarationStatus`
**Zasada:** suma wpłat ≥ kwota obiecana → `FULFILLED`; suma dodatnia, ale mniejsza → `PARTIALLY_FULFILLED`; brak wpłat → powrót do `PENDING` albo `BROKEN`, zależnie od tego, czy termin minął.
**Dlaczego:** status liczony z zawartości tabeli wpłat, a nie z parametru, obsługuje tak samo rejestrację i wycofanie. Windykator nie może oznaczyć deklaracji jako dotrzymanej bez wpłaty w systemie.

### BR-36 — Deklaracja i sprawa muszą należeć do tego samego dłużnika

**Gdzie:** `PaymentService.loadDeclarationIfLinked`

---

## Wskaźniki

### BR-40 — Skuteczność liczona tylko z deklaracji rozstrzygniętych

**Wzór:** `FULFILLED / (FULFILLED + PARTIALLY_FULFILLED + BROKEN)`
**Dlaczego:** deklaracje oczekujące jeszcze się nie rozstrzygnęły, a anulowane zostały wycofane i nie świadczą o zachowaniu dłużnika. Włączenie ich rozwadniałoby wskaźnik przy każdej serii świeżo wpisanych obietnic.

### BR-41 — Suma deklaracji pomija złamane i anulowane

**Gdzie:** `DeclarationService.sumByDebtorId`
**Dlaczego:** odpowiada na pytanie „na ile realnie mogę liczyć", a nie „ile mi naobiecywano".

---

## Integralność danych

### BR-50 — Usunięcie dłużnika kasuje całą jego historię

**Mechanizm:** `ON DELETE CASCADE` na sprawach, deklaracjach, kontaktach, telefonach i audycie.
**Zabezpieczenie:** interfejs wylicza konsekwencje przed potwierdzeniem.
**Ograniczenie:** brak archiwum i możliwości cofnięcia — patrz „Znane ograniczenia" w README.

### BR-51 — Usunięcie sprawy nie kasuje deklaracji

**Mechanizm:** `ON DELETE SET NULL` na `declarations.debt_id`
**Dlaczego:** obietnica wpłaty jest zobowiązaniem osoby, nie sprawy. Deklaracja przeżywa usunięcie sprawy i pozostaje w historii bez przypisania.

### BR-52 — Migracja schematu jest transakcyjna

**Gdzie:** `SchemaManager`
**Dlaczego:** błąd w dowolnej instrukcji cofa całą migrację i zostawia bazę w poprzedniej, spójnej wersji.
**Szczegół:** migracje przebudowujące tabelę wymagają wyłączenia kluczy obcych, bo `DROP TABLE` uruchamia akcje `ON DELETE` tabel zależnych. Spójność sprawdzana jest przez `PRAGMA foreign_key_check` przed zatwierdzeniem.
