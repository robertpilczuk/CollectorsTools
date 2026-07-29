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

## Etap 5c — testy interfejsu i rozbicie salda w formularzu

Dwa braki zgłoszone przy zamykaniu Etapu 5b: logika dialogów nie była objęta testami,
a formularz sprawy nie pozwalał wpisać rozbicia salda, przez co edycja sprawy z importu
kończyła się odrzuceniem przez regułę BR-12.

### TestFX bez robota

Dodane `testfx-core` i `testfx-junit5`. Testy **nie sterują myszą ani klawiaturą** —
uruchamiają toolkit JavaFX, tworzą dialog na wątku aplikacji, ustawiają wartości pól
i sprawdzają stan przycisku zapisu oraz wynik zwracany przez formularz.

Powód: sprawdzana jest logika, nie wygląd. Klikanie robotem byłoby wolniejsze
i znacznie bardziej kruche, a nie dałoby nic ponad to.

Pola dialogów dostały identyfikatory (`#principal`, `#closureStatus`…), żeby testy mogły
je odnaleźć w drzewie sceny bez sięgania do prywatnych pól klasy.

Pokrycie:

- **`DebtFormDialogTest`** — 8 testów, w tym **regresja na błąd z Etapu 5b**: edycja
  sprawy sprawdzana pod kątem zachowania jedenastu pól spoza formularza, łącznie
  ze stanem zamknięcia, datą i przyczyną
- **`CaseClosureDialogTest`** — 8 testów reguł BR-13 i BR-14 na poziomie interfejsu

### Rozbicie salda w formularzu sprawy

Doszły pola kapitał, odsetki i koszty wraz z komunikatem pokazującym na bieżąco, czy
suma zgadza się z kwotą pozostałą. Rozbicie pozostaje opcjonalne — puste pola oznaczają
„nieznane", a nie zera.

**Błąd znaleziony przy okazji:** `parseBigDecimal` rzucał wyjątkiem na pustym polu.
Nowe pola są opcjonalne i podpięte pod listener reagujący na każde naciśnięcie klawisza,
więc formularz wywaliłby się przy pierwszym wpisanym znaku. Parsowanie zwraca teraz
`null` zamiast rzucać — pole w trakcie pisania przechodzi przez stany, które nie są
jeszcze liczbą (sam minus, sam przecinek).

Testy: 115 → 131.

**Do rozwiązania w Etapie 11:** testy JavaFX wymagają dostępnego ekranu. Na maszynie
deweloperskiej działają wprost; w CI na Linuksie trzeba je uruchamiać przez `xvfb-run`
albo skonfigurować tryb headless.

---

## Etap 6 — dziennik audytowy

Istniejąca tabela `debtor_audit` rejestrowała wyłącznie zmiany pól dłużnika i była
przywiązana do jednej encji kluczem obcym. Zmiany o skutkach finansowych — wpłaty,
zamknięcia spraw, przejścia stanów deklaracji, usunięcia — nie zostawiały żadnego śladu.

### Dwie decyzje projektowe

**Brak kluczy obcych w `audit_log` jest celowy.** Wpis musi przeżyć usunięcie obiektu,
którego dotyczy. Klucz obcy z `ON DELETE CASCADE` skasowałby ślad po usunięciu dłużnika,
czyli dokładnie ten wpis, którego szuka się najczęściej. Kolumny `entity_id` i `debtor_id`
są zwykłymi liczbami, bez więzów. Pilnuje tego osobny test.

**`debtor_id` jest denormalizacją.** Pozwala pokazać pełną historię dłużnika jednym
zapytaniem, zamiast łączyć audyt wpłat przez sprawy, a deklaracji przez dłużnika.

Autor wpisu pochodzi z `CurrentUser` — jednego użytkownika lokalnego. Kolumna `user_id`
istnieje od początku, więc wprowadzenie logowania sprowadzi się do podmiany źródła tej
wartości, bez migracji danych i bez zmian w serwisach.

### Luka znaleziona przez test

Test sprawdzający, czy historia dłużnika obejmuje zdarzenia ze wszystkich encji, wykrył,
że **zmiana stanu deklaracji wywołana wpłatą nie była audytowana**. `PaymentService`
aktualizuje deklarację bezpośrednio przez repozytorium, z pominięciem `DeclarationService`,
więc przejście „Oczekująca → Dotrzymana" następowało bez śladu — mimo że to jedno
z najważniejszych zdarzeń w całym procesie. Wpis powstaje teraz w miejscu faktycznej zmiany.

### Kompromis: konstruktory bez audytu

Serwisy dostały dodatkowe konstruktory przyjmujące `AuditService`. Dotychczasowe
konstruktory jednoargumentowe, używane przez istniejące testy, przekazują wariant
`AuditService.noOp()`, który niczego nie zapisuje.

Zaleta: żadnego testu nie trzeba było przepisywać. Ryzyko: użycie starszego konstruktora
w kodzie produkcyjnym oznaczałoby brak audytu. Ścieżki produkcyjne przechodzą wyłącznie
przez konstruktory bezargumentowe, które zawsze wpinają działającą instancję —
udokumentowane w `CLAUDE.md`.

### Objęte audytem

wpłata i jej wycofanie · zamknięcie i ponowne otwarcie sprawy · każda zmiana stanu
deklaracji, także wywołana wpłatą · usunięcie dłużnika i sprawy

Zakładka „Historia zmian" łączy oba źródła w jedną oś czasu — dziennik audytowy i starszą
tabelę zmian pól dłużnika. Operacje finansowe i decyzje o stanie sprawy są pogrubione.

Testy: 131 → 141.

---

## Etap 7 — obsługa błędów i logowanie

Stan wyjściowy: jedynymi „logami" były wywołania `System.out.println`, a większość wywołań
serwisów w interfejsie nie miała żadnego przechwytywania. Wyjątek na wątku JavaFX kończył
się tym, że aplikacja pozornie nie robiła nic — użytkownik klikał i nie działo się nic,
bez komunikatu i bez śladu.

### Logowanie, które po cichu nie działało

Po dodaniu Logbacka testy przeszły, ale **plik logu nie powstał**. Przyczyna: `sqlite-jdbc`
ciągnie `slf4j-api` w wersji **1.7.36**, a Logback 1.5.6 wymaga 2.x. Mediacja Mavena wybrała
starszą wersję, przez co implementacja nie została odnaleziona i logi szły w pustkę —
dokładnie ten rodzaj awarii, o który w tym etapie chodzi.

Rozwiązanie: jawna deklaracja `slf4j-api` 2.0.13 jako zależności bezpośredniej. Gdyby nie
sprawdzenie, czy plik faktycznie powstał, projekt miałby konfigurację logowania, która
niczego nie zapisuje.

Testy dostały własny `logback-test.xml` na poziomie WARN — przy 146 testach, z których każdy
stosuje komplet migracji na własnej bazie, poziom INFO generował 100 KB logu bez żadnej
wartości diagnostycznej.

### Dwa rodzaje błędów

**`BusinessRuleException`** — sytuacja przewidziana, którą użytkownik naprawi. Komunikat
trafia na ekran bez zmian: żadnych nazw klas, kodów SQL ani stosu wywołań. Dziedziczy
po `IllegalArgumentException`, więc wszystkie istniejące miejsca przechwytujące wyjątki
i testy działają bez przepisywania. Zamienionych 40 wystąpień w serwisach.

**`TechnicalException`** — awaria, której użytkownik nie naprawi zmianą danych w formularzu.
Rozdziela komunikat: `getUserMessage()` mówi po ludzku co się stało i co zrobić, a szczegóły
techniczne idą do logu. Fabryka `TechnicalException.database(…)` trzyma jeden układ:
**co się nie udało → jaki jest skutek dla danych → co zrobić dalej**. Skutek jest istotny —
dzięki transakcjom nieudana operacja nie zostawia połowicznego zapisu, a użytkownik powinien
to wiedzieć, zanim zacznie sprawdzać, czy „coś się zapisało".

### `ErrorHandler`

Jedno miejsce, przez które błędy trafiają do użytkownika i do logu. Reguła biznesowa →
ostrzeżenie z komunikatem, w logu wpis bez stosu. Awaria techniczna → komunikat zrozumiały
dla użytkownika, pełny stos w pliku i do rozwinięcia w oknie „Szczegóły dla zgłoszenia".

Wpina też globalny przechwytywacz wyjątków na wątku JavaFX. Wywołania serwisów w oknach
przeszły na `ErrorHandler.run(...)`, co zastąpiło powtarzalne `try/catch` łapiące wyłącznie
`IllegalArgumentException` — awarie techniczne wcześniej przez nie przelatywały.

### Awaria bazy przy starcie

Jedyny błąd, po którym aplikacja nie ma jak działać. Zamiast stosu w konsoli, której
użytkownik nigdy nie zobaczy, pojawia się okno z przyczyną i konkretnym wyjściem:
**wskazaniem kopii zapasowej**. Kopia zostaje skopiowana na miejsce bieżącej bazy,
a aplikacja prosi o ponowne uruchomienie.

Testy: 141 → 146.

---

## Etap 8a — parser kolumny „Komentarz do sprawy"

Kolumna ma średnio 497 znaków i zawiera dane, których nie ma w żadnej innej kolumnie:
ostatnią wpłatę, obietnicę spłaty, kategorię sprawy, zgodę na ugodę, status kredytu
i dane o zatrudnieniu.

### Najpierw pomiar, potem kod

Przed napisaniem parsera zebrane zostały wzorce zapisu ze wszystkich 171 wierszy.
Bez tego powstałby parser dopasowany do dwóch przykładów widzianych wcześniej.

Pomiar pokazał podział na dwie warstwy:

- **sekcje z etykietami** — format całkowicie regularny, np. ostatnia wpłata zawsze jako
  `RRRR-MM-DD kwota`; 167 ze 171 wierszy
- **treść ostatniego kontaktu** — swobodna notatka pisana w pośpiechu, w której obietnica
  spłaty pojawia się w kilkunastu wariantach

Warianty obietnicy spotkane w danych: kwota przed terminem i termin przed kwotą, waluta
zapisana jako `PLN`, `ZŁ`, `ZL` albo wcale, separatory daty `,` `.` `/`, kwota otoczona
znacznikami `#`, przyimek `DO` przed terminem, dwie daty w jednym zdaniu oraz —
najważniejsze — **zaprzeczenia** w rodzaju „OBIETNICY SPŁATY NIE MA".

### Zasada: parser nie zgaduje po cichu

Odczyt dostaje poziom pewności. `HIGH` to kwota i termin odczytane jednoznacznie,
`MEDIUM` to kwota bez terminu, `LOW` to zapis wymagający decyzji człowieka. Każde
zastrzeżenie trafia do listy `warnings` razem z oryginalnym fragmentem notatki,
żeby dało się sprawdzić odczyt.

Jednostka waluty jest jedynym pewnym rozróżnieniem między kwotą a datą: zapis `31,12`
może znaczyć jedno i drugie. Dlatego najpierw szukamy kwoty przy walucie, wycinamy ją
z tekstu, a terminu szukamy dopiero w tym, co zostanie.

Rok w terminach zapisanych bez roku ustalany jest względem daty eksportu: termin
wypadający ponad trzy miesiące w przyszłość dotyczy roku poprzedniego.

### Dwa błędy znalezione przez testy

**Wzorzec fragmentu urywał się na ukośniku.** Notatki używają `/` jako separatora myśli,
więc pierwotnie ograniczał on fragment obietnicy. Problem w tym, że ukośnik występuje też
w terminach zapisanych jako `16/01` — obcięcie zabierało dacie miesiąc i termin przepadał.

**Separator tysięcy w kwocie tworzył fałszywe odczyty.** W zapisie
`OBIETNICA SPŁATY 10,02 100 PLN` wzorzec dopuszczający spację w liczbie sklejał końcówkę
daty z kwotą i odczytywał 2100 zamiast 100. W badanym portfelu kwoty z separatorem tysięcy
nie wystąpiły ani razu, więc obsługa została usunięta.

Dwa kolejne błędy okazały się błędami w moich oczekiwaniach, nie w kodzie — dotyczyły
terminów mieszczących się w tolerancji trzech miesięcy.

### Skuteczność na rzeczywistym pliku

| Pole | Odczytane |
|---|---|
| ostatnia wpłata | 167 / 171 |
| kategoria sprawy, status kredytu, zgoda na ugodę | 166 / 171 |
| pracodawca | 91 / 171 |
| data wszczęcia egzekucji | 53 / 171 |

Obietnice: **74 wykryte, w tym 68 z pełną pewnością** (92%), 2 bez terminu, 4 do przeglądu.
Dodatkowo 3 zaprzeczenia rozpoznane poprawnie jako brak obietnicy — bez tego powstałyby
trzy deklaracje, których dłużnicy nigdy nie złożyli.

Przegląd przypadków `LOW` ujawnił wariant w pełni ustrukturyzowany
(`KWOTA OBIETNICY: 300, DATA OBIETNICY: 2016-06-30`), występujący raz. Obsługa kosztowała
sześć linii i jest całkowicie pewna, więc została dodana.

Testy: 146 → 169.

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
