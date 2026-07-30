# Dane demonstracyjne

Aplikację widać w działaniu dopiero na danych o realnym kształcie: 171 spraw, 155 osób,
kilkanaście kategorii, salda od 25 zł do 266 tys. zł. Takie dane pochodzą z pliku zlecenia
wierzyciela — i właśnie dlatego nie mogą trafić do repozytorium w oryginale.

Do repozytorium wchodzi wyłącznie plik po anonimizacji:
`src/main/resources/demo/zlecenie-demo.xlsx`.

---

## Co zostaje, a co znika

| Dane | Co się z nimi dzieje | Dlaczego |
|---|---|---|
| Imię i nazwisko | Wymienione na zastępcze | Wskazują osobę |
| PESEL | Wymieniony na inny, poprawny formalnie | Wskazuje osobę; import odrzuca numery błędne, więc zastępczy musi mieć poprawną sumę kontrolną |
| Ulica, nr domu, nr lokalu, kod pocztowy | Wymienione | Wskazują osobę |
| Miejscowość | **Zostaje** | Nie wskazuje osoby, a niesie rozkład geograficzny portfela (82 miejscowości, Lublin 39) |
| Telefony | Wymienione, liczba zachowana | Wskazują osobę |
| Nr sprawy, nr rachunku, sygnatura sądowa | Wymienione | Numeracja wewnętrzna wierzyciela |
| Pracodawca: nazwa, adres, telefon | Wymienione | Wskazują osobę pośrednio |
| Kwoty: saldo, kapitał, odsetki, koszty | **Zostają** | To one czynią dane wartościowymi; niezmieniona zostaje też reguła `saldo = kapitał + odsetki + koszty` |
| Daty: obsługi, wpłat, egzekucji | **Zostają** | Bez nich nie ma osi czasu |
| Wierzyciel, portfel, produkt, kategoria, status kredytu, zgoda na ugodę | **Zostają** | Słowniki wierzyciela, nie dane osobowe |
| Swobodne notatki windykatora | Zastąpione tekstem zastępczym | Mogą zawierać dowolną treść, także o osobach trzecich |
| Obietnica spłaty odczytana z notatki | **Zostaje**, przepisana w jednoznacznej postaci | To z niej powstają deklaracje — 68 sztuk w pliku demo |

**Zasada nadrzędna: nierozpoznane znaczy usunięte.** Kolumna, której anonimizator nie
umie zakwalifikować, i sekcja komentarza spoza znanej listy są czyszczone, a nie
przepuszczane. Odwrotna domyślność oznaczałaby, że nowa kolumna w eksporcie wierzyciela
po cichu wynosi dane osobowe do publicznego repozytorium.

## Tożsamość zastępcza jest stała dla osoby

Ten sam PESEL źródłowy daje wszędzie tę samą osobę zastępczą. Bez tego 16 osób, które
w portfelu miały po kilka spraw, rozpadłoby się na 16 osobnych dłużników — a plik demo
przestałby pokazywać relację jeden dłużnik : wiele spraw, czyli to, wokół czego zbudowana
jest cała aplikacja.

Stałość nie oznacza odwracalności: wartość zastępcza powstaje z ziarna wyliczonego
z oryginału, ale nie niesie żadnego jego fragmentu.

Wartości zastępcze nie mogą też trafić w oryginał — nazwisko zastępcze nigdy nie pokrywa
się z prawdziwym nazwiskiem z tego samego wiersza, ulica z prawdziwą ulicą, a adresy
wymyślone wskazują nieistniejącą miejscowość „Wólka Przykładowa".

---

## Jak wygenerować plik na nowo

Plik źródłowy leży poza repozytorium (katalog `data/` jest w `.gitignore`).

```bash
JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -q compile dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt

java -cp "target/classes:$(cat /tmp/cp.txt)" com.collectorstools.demo.DataAnonymizer \
     "data/Zlecenie - Lubelskie 08.02.2017.xlsx" \
     src/main/resources/demo/zlecenie-demo.xlsx 2017-02-08
```

Trzeci argument to data sporządzenia eksportu. Jest potrzebna, żeby odczytać terminy
zapisane bez roku (`OBIETNICA SPŁATY 50 PLN 31,12`) przed przepisaniem ich w postaci
jednoznacznej.

Narzędzie wypisuje raport: ile wierszy, ile osób, ile komentarzy przepisano, ile obietnic
zachowano oraz **co zostało usunięte jako nierozpoznane**. Ostatnia pozycja jest
najważniejsza — to ona pokazuje, że w eksporcie pojawiło się coś, czego anonimizator
nie zna.

## Jak sprawdzić, że nic nie przeciekło

```bash
java -cp "target/classes:$(cat /tmp/cp.txt)" com.collectorstools.demo.LeakCheck \
     "data/Zlecenie - Lubelskie 08.02.2017.xlsx" \
     src/main/resources/demo/zlecenie-demo.xlsx
```

Kontrola porównuje pliki **wiersz po wierszu**: szuka wartości z pól wrażliwych źródła
w tym samym wierszu pliku demo. Przypadkowa zbieżność nazwiska między różnymi osobami
niczego nie ujawnia — dopiero ta sama wartość w tym samym wierszu oznacza, że oryginał
przeżył anonimizację. Wynik wypisywany jest zamaskowany, bo raport też jest danymi.

Stan oczekiwany: `Przecieków: 0`. Kontrola kończy się kodem wyjścia 2, jeśli coś znajdzie.

To narzędzie wykryło trzy nieszczelności pierwszej wersji anonimizatora: nazwisko zastępcze
trafiające w prawdziwe, ulicę zastępczą trafiającą w prawdziwą oraz — najpoważniejszą —
sekcję zatrudnienia przepuszczaną w całości, gdy pracodawcy nie podano. W tej ostatniej
obok „NIE DOTYCZY" w polu nazwy stał w kilku wierszach prawdziwy adres.

## Czego plik demo nie pokazuje

- **Swobodne notatki są zastępcze.** Import z pliku demo nie ćwiczy tych ścieżek parsera,
  które radzą sobie z zapisem niechlujnym; te pokrywają testy `CaseCommentParserTest`.
- **Obietnic jest 68, nie 77.** Do postaci jednoznacznej dają się przepisać tylko te
  odczytane z kwotą i terminem. Pozostałe i tak nie tworzyły deklaracji.
- **Kolumny puste w pliku klienta** (`Adres 3`, `Wierzyciel pierwotny`, `Sygnatura sądowa`,
  `Sąd`, `notatka z wizyty`) są puste również w demo.

## Testy

- `DataAnonymizerTest` — reguły anonimizacji na danych syntetycznych
- `DemoDataFileTest` — dołączony plik demo: 171 wierszy, zero odrzuceń przy imporcie,
  poprawne numery PESEL, powtórzenia osób, zgodność salda z rozbiciem oraz obecność
  danych, dla których ten plik istnieje (obietnice, wpłaty, telefony, wizyty)
