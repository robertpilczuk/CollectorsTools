# Dziennik prac — przebudowa CollectorsTools

Zapis kolejnych etapów przebudowy aplikacji: co zostało zrobione, jakie problemy
wyszły w trakcie i dlaczego podjęto takie, a nie inne decyzje. Prowadzony na bieżąco,
jeden wpis na etap.

Punkt wyjścia: działająca aplikacja JavaFX + SQLite z 39 testami, poprawną
architekturą warstwową i **jedną poważną luką biznesową** — rejestracja wpłaty
nie zmieniała salda sprawy.

---

## Etap 0 — repozytorium i punkt odniesienia

**Commit:** `b0fcddf` · `chore(repo): initialize repository with existing application baseline`

Stan zastany: katalog `CollectorsTools-master` (rozpakowany ZIP), **brak repozytorium git**,
brak `.gitignore`, w katalogu artefakty builda i plik bazy z danymi.

Wykonane:

- baseline `mvn clean test` przed jakąkolwiek zmianą — **39 testów, BUILD SUCCESS**
- `git init`, `.gitignore` (build, baza, logi, pliki IDE, paczki dystrybucyjne)
- katalog przemianowany na `CollectorsTools`
- podział `CLAUDE.md`: konwencje kodu zostają w repozytorium, workflow i backlog
  przechodzą do nieśledzonego `CLAUDE.local.md`

**Problem środowiskowy:** na maszynie nie było JDK ani Mavena. Po instalacji okazało się,
że formuła `maven` z Homebrew ciągnie własny, nowszy JDK, z którym Lombok 1.18.32 się nie
kompiluje — build wywalał się mylącym `TypeTag :: UNKNOWN`. Każde wywołanie Mavena wymaga
jawnego `JAVA_HOME` wskazującego Javę 21. Do naprawienia w Etapie 10 przez
`maven-enforcer-plugin`.

**Ustalenie:** zdalne repozytorium zawierało inny projekt — konsolowy prototyp z 2020 roku
(80 linii, zależności `gson`/`unirest`) plus trzy nieaktualne pull requesty. Historia
została zastąpiona, gałęzie usunięte, PR-y zamknięte.

---

## Etap 1 — migracje jako jedno źródło prawdy o schemacie

**Commit:** `684f154` · `refactor(db): extract schema to versioned sql migrations`

**Problem:** definicje tabel istniały w dwóch miejscach — `DatabaseManager.initSchema()`
i `DatabaseTestHelper.createSchema()`. Każda zmiana schematu wymagała edycji obu plików,
co przy zaplanowanych czterech migracjach było gwarancją rozjazdu testów z produkcją.

Rozwiązanie: pliki SQL w `src/main/resources/db/migrations` jako **jedyne** źródło
schematu, stosowane tak samo przez aplikację i testy.

- wersja schematu w `PRAGMA user_version` — w samym pliku bazy, bez dodatkowej tabeli
- każda migracja w osobnej transakcji: błąd cofa całość i zostawia poprzednią, spójną wersję
- obsługa baz sprzed wersjonowania (dokładanie kolumny `urgent`)

Efekt: `DatabaseManager` mniejszy o 112 linii, `DatabaseTestHelper` o 81. Razem
**178 linii usuniętych, 15 dodanych**.

Zweryfikowane na kopii rzeczywistej bazy: wersja 0 → 1, dane nienaruszone,
brakująca tabela `debtor_audit` dotworzona.

**Odrzucona alternatywa:** trzymanie pełnego `schema.sql` obok migracji. Odtworzyłoby
dokładnie ten problem duplikacji, który usuwaliśmy. Świeża baza powstaje przez zastosowanie
migracji po kolei.

---

## Etap 2 — transakcje

**Commit:** `1fb4fff` · `feat(db): add transaction manager for atomic multi-table operations`

**Problem:** zero transakcji w całym projekcie. Wszystkie repozytoria pracowały na
autocommit, więc operacja złożona z kilku zapisów mogła zostać przerwana w połowie.
Bez tego mechanizmu nie dało się poprawnie zaimplementować wpłaty.

`TransactionManager` — trzy decyzje projektowe:

1. **Zagnieżdżenia dołączają, nie otwierają nowej transakcji.** Aplikacja pracuje na jednym
   współdzielonym połączeniu, więc prawdziwe transakcje zagnieżdżone nie istnieją.
   O zatwierdzeniu decyduje wywołanie najbardziej zewnętrzne — bez tego serwis wołający
   inny serwis zatwierdzałby transakcję w połowie cudzej operacji.
2. **Wyjątki biznesowe propagują się nietknięte**, żeby interfejs mógł pokazać oryginalny
   komunikat. Opakowywany jest wyłącznie `SQLException`.
3. **Rollback nie przesłania pierwotnego błędu** — nieudane wycofanie trafia na wyjście
   diagnostyczne, w górę leci oryginalna przyczyna.

6 nowych testów, w tym rollback po wymuszonym naruszeniu klucza obcego.

---

## Etap 3 — rozszerzone statusy deklaracji

**Commit:** `2fa8063` · `feat(model): extend declaration statuses with partial and cancelled states`

Statusy były zapisywane po polsku i pokrywały trzy stany, co nie pozwalało odnotować
wpłaty częściowej, wycofać deklaracji ani zarejestrować wpłaty po terminie.

Nowy zestaw: `PENDING · FULFILLED · PARTIALLY_FULFILLED · BROKEN · CANCELLED` —
nazwy angielskie w kodzie i bazie, polskie w interfejsie.

Migracja V2 przebudowuje tabelę (SQLite nie zmieni `DEFAULT` ani nie doda `NOT NULL`
bez wartości domyślnej), mapuje stare wartości i uzupełnia `created_at` datą obietnicy —
faktyczna data rejestracji nigdy nie była zapisywana.

Reguły biznesowe:

- **wpłata po terminie domyka złamaną deklarację**, zachowując powód złamania; interfejs
  pokazuje „Dotrzymana (po terminie)" na podstawie porównania dat, bez dodatkowej kolumny
- **anulowanie wymaga uzasadnienia** — anulowana deklaracja wypada ze wskaźnika
  skuteczności, więc bez tego wymogu byłaby to furtka do poprawiania statystyk
- **automatyczne łamanie omija stany częściowy i anulowany** — jeśli dłużnik wpłacił część,
  o niedotrzymaniu decyduje windykator, nie zegar

Usunięty martwy kod: `Declaration.refreshStatus()` duplikował regułę zaimplementowaną
w SQL i nie był nigdzie wywoływany.

Testy: 50 → 63.

---

## Etap 4a — model sprawy zgodny z eksportem wierzyciela

**Commit:** `254a933` (wspólny dla etapów 4a, 4b i 4c — patrz uwaga na końcu dziennika)

Zakres wyznaczyła analiza rzeczywistego pliku klienta (171 spraw, 26 kolumn, eksport
z lutego 2017). Do `debts` doszło 14 kolumn: rozbicie salda na kapitał/odsetki/koszty,
portfel, produkt, rachunek, wierzyciel pierwotny, kategoria sprawy, status kredytu,
zgoda na ugodę, sąd i sygnatura, daty rozpoczęcia obsługi i wszczęcia egzekucji.

**Pułapka SQLite, którą trzeba było rozbroić przed migracją.** Tabela wymagała przebudowy,
bo `CHECK` nie da się dodać przez `ALTER TABLE`. Przy włączonych kluczach obcych
`DROP TABLE` wykonuje niejawne `DELETE FROM` i **uruchamia akcje `ON DELETE` tabel
zależnych** — `declarations.debt_id` ma `ON DELETE SET NULL`, więc przebudowa po cichu
zerwałaby powiązanie wszystkich deklaracji ze sprawami. Dane formalnie by przetrwały,
a problem ujawniłby się dopiero przy pierwszym raporcie.

`SchemaManager` wyłącza teraz klucze obce na czas migracji — koniecznie **poza transakcją**,
bo `PRAGMA foreign_keys` jest wewnątrz niej ignorowana — i weryfikuje spójność przez
`PRAGMA foreign_key_check` przed zatwierdzeniem.

Reguła z danych: **saldo = kapitał + odsetki + koszty** zgadzało się co do grosza we
wszystkich 171 sprawach. Walidacja z tolerancją grosza, uruchamiana tylko gdy rozbicie
jest znane.

Dodatkowo tabela `debtor_phones` (w portfelu do 10 numerów na sprawę) z wyszukiwaniem
ignorującym spacje, myślniki i prefiks `+48`.

Testy: 63 → 81.

---

## Etap 4b — wpłaty i saldo

**Commit:** `254a933` (wspólny dla etapów 4a, 4b i 4c)

Zamknięcie głównej luki biznesowej: do tej pory aplikacja znała wyłącznie kwotę
**obiecaną**. Kwota faktycznie wpłacona nie była nigdzie zapisywana, a saldo sprawy
pozostawało nietknięte po oznaczeniu deklaracji jako dotrzymanej — statystyka
„Suma długów" była trwale zawyżona.

**Zaliczanie wpłaty według art. 451 § 1 KC:** koszty → odsetki → kapitał. Podział
zapisywany jest przy wpłacie w kolumnach `allocated_*`, dzięki czemu wycofanie błędnej
wpłaty odtwarza saldo dokładnie, bez zgadywania.

Reguły:

| Sytuacja | Zachowanie |
|---|---|
| nadpłata | odrzucona z podaniem maksymalnej kwoty; brak śladu w ewidencji |
| wpłata pełna | deklaracja → `FULFILLED` |
| wpłata częściowa | deklaracja → `PARTIALLY_FULFILLED`; dopłata domyka |
| wpłata po terminie | złamana deklaracja wraca do `FULFILLED`, powód zachowany |
| deklaracja anulowana | wpłaty przyjąć się nie da |

Status deklaracji wyliczany jest **wyłącznie z zawartości tabeli wpłat**, nigdy z parametru —
dzięki temu ta sama metoda obsługuje rejestrację i wycofanie.

**Wyłapane przez testy:** komunikat o nadpłacie pokazywał „1000.0 zł", bo SQLite trzyma
kwoty jako `REAL`. Dodane formatowanie pieniężne — dwa miejsca po przecinku i przecinek
dziesiętny.

Testy: 81 → 100.

---

## Etap 4c — interfejs wpłat

**Commit:** `254a933` (wspólny dla etapów 4a, 4b i 4c)

Najważniejsza zmiana: **przycisk „✓ Wpłata" przy deklaracji nie oznacza jej już ręcznie
jako dotrzymanej** — otwiera formularz rejestracji wpłaty. Status deklaracji wynika odtąd
wyłącznie z faktycznie zaksięgowanej kwoty, więc saldo i status nie mogą się rozjechać.

Formularz podpowiada kwotę brakującą do wypełnienia deklaracji (ograniczoną saldem sprawy),
pokazuje pozostałe saldo na bieżąco i ostrzega pomarańczowym komunikatem, gdy data wpłaty
wypada po terminie — nie blokując zapisu.

Nowa zakładka „Wpłaty" z kolumną **„Zaliczono (K / O / Kap.)"** — przy rozmowie z dłużnikiem
o tym, dlaczego kapitał zmalał mniej niż wpłacona kwota, to konkretna odpowiedź.
Przycisk „Wycofaj" cofa błędną wpłatę i przelicza powiązaną deklarację.

Zweryfikowane uruchomieniem aplikacji: cztery migracje zastosowały się na rzeczywistej
bazie, zero wyjątków runtime.

---

## Etap 5a — reguły biznesowe i katalog reguł

**Commit:** `feat(service): enforce case closure rules and protect payment history`

**Błąd znaleziony przez testy.** Zamykanie sprawy wywalało się komunikatem
„Rozbicie salda nie zgadza się z kwotą pozostałą: 0.0 + 0.0 + 0.0 = 0.0, oczekiwano 1000.0".

Przyczyna: kolumny składników salda są `NOT NULL DEFAULT 0`, więc sprawa zapisana **bez**
rozbicia wraca z odczytu z zerami, a nie z wartościami pustymi. Walidacja z Etapu 4a
rozpoznawała „brak rozbicia" po `null` — działało to wyłącznie do pierwszego odczytu z bazy,
potem blokowała każdą aktualizację takiej sprawy, w tym zamknięcie.

Poprawne rozpoznanie: **zerowa suma składników** — ten sam warunek, którego używał już
`PaymentService`. Dodany test regresyjny. Zasada ogólna: przy `NOT NULL DEFAULT` nigdy nie
odróżniaj „nie podano" od „podano zero" przez `null`.

Statusy sprawy: `ACTIVE · CLOSED_PAID · CLOSED_WRITTEN_OFF · CLOSED_LEGAL ·
CLOSED_TIME_BARRED`. Kategoria „przedawniona" nie jest wymyślona — w eksporcie klienta
były sprawy oznaczone jako `Z_PRZEDAWN`.

Reguły:

- zamknięcie z niespłaconym saldem **wymaga przyczyny**
- jako spłaconą można zamknąć **tylko sprawę z saldem zerowym**
- spłata całości zamyka sprawę automatycznie, ale **nie nadpisuje** zamknięcia ustawionego
  ręcznie (umorzenie, droga sądowa)
- sprawy z wpłatami **nie da się usunąć** — komunikat wskazuje alternatywę
- termin deklaracji nie może poprzedzać daty jej zarejestrowania

Powstał `docs/business-rules.md` — katalog **36 reguł** w formacie: reguła → gdzie
egzekwowana → dlaczego → komunikat dla użytkownika, z podstawą prawną tam, gdzie istnieje.

Testy: 100 → 115.

---

## Etap 5b — interfejs zamykania spraw

**Błąd znaleziony przy przeglądzie ścieżki edycji.** Formularz sprawy budował przy zapisie
**nowy** obiekt z sześciu widocznych pól. Oznaczało to, że edycja dowolnej sprawy kasowała
wszystko, czego formularz nie pokazuje: rozbicie salda, portfel, produkt, rachunek, dane
sądowe, daty obsługi i egzekucji — a od Etapu 5a również **stan zamknięcia**. Zamknięta
sprawa po zwykłej korekcie opisu cicho wracałaby do obsługi, tracąc datę i przyczynę
zamknięcia.

Wada powstała w Etapie 4a wraz z dodaniem pól, których formularz nie obejmuje, i pozostawała
niewidoczna, bo nic wcześniej tych pól nie wypełniało. Poprawka: przy edycji formularz
nadpisuje **wyłącznie** pola, które sam pokazuje, pracując na istniejącym obiekcie.

Wnioski na przyszłość: dodanie pola do modelu wymaga sprawdzenia każdego miejsca, które
buduje ten model od zera. Formularze nadpisujące całe encje są w tym wzorcu szczególnie
podatne.

Zaimplementowane:

- **`CaseClosureDialog`** — wybór stanu końcowego i uzasadnienia. Zamknięcie „spłacona"
  **znika z listy**, gdy sprawa ma niespłacone saldo, zamiast kończyć się błędem po
  zatwierdzeniu. Przy dodatnim saldzie uzasadnienie jest wymagane, a przycisk pozostaje
  nieaktywny do jego wpisania.
- kolumna **„Stan"** w tabeli spraw, kolorowana wg statusu, z przyczyną zamknięcia
  w podpowiedzi
- przyciski **„Zamknij" / „Otwórz"** przełączane zależnie od stanu sprawy
- obsługa odmowy usunięcia sprawy z wpłatami — komunikat zamiast cichego braku reakcji
- **dialog usunięcia dłużnika** wyliczający konsekwencje (sprawy, deklaracje, kontakty,
  wpłaty wraz z kwotą) i wymagający przepisania nazwiska

Ostatnia zmiana wynika wprost z braku archiwum i kosza w aplikacji: usunięcie dłużnika
kasuje kaskadowo całą historię i nie da się go cofnąć, więc ochrona przed odruchowym
kliknięciem „OK" jest ważniejsza niż wygoda.

**Nieprzetestowane automatycznie:** logika dialogów nie jest objęta testami — projekt nie
ma harnessu do testowania JavaFX. Weryfikacja odbywa się przez uruchomienie aplikacji.

---

## Uwaga do historii commitów

Etapy 4a, 4b i 4c trafiły do **jednego commita** `254a933`, którego opis odnosi się
wyłącznie do warstwy interfejsu. Przyczyna: po zatwierdzeniu etapu praca przechodziła
od razu do implementacji następnego, bez commita pośredniego.

Zawartość `254a933` obejmuje w rzeczywistości:

- migrację V3 — rozszerzenie sprawy o pola z eksportu wierzyciela, tabela `debtor_phones`
- migrację V4 — tabela `payments`
- `PaymentService` wraz z zaliczaniem wpłat i wycofywaniem
- interfejs: formularz wpłaty, zakładka „Wpłaty"
- 37 testów

Pozostawione bez korekty, żeby nie przepisywać opublikowanej historii. Kolejne etapy
są commitowane pojedynczo.
