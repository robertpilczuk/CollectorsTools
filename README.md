# CollectorsTools

Aplikacja desktopowa wspomagająca pracę windykatorów terenowych.  
Umożliwia zarządzanie dłużnikami, ich sprawami, deklaracjami spłat oraz historią kontaktów.

---

## Dla windykatora — jak uruchomić aplikację

### Wymagania
- **Java 21** — do pobrania ze strony: https://adoptium.net (kliknij "Latest LTS Release")
- **Maven** — do pobrania ze strony: https://maven.apache.org/download.cgi

### Pierwsze uruchomienie

1. Pobierz projekt (jako ZIP z GitHub lub sklonuj repozytorium)
2. Otwórz terminal w folderze projektu
3. Wpisz:
```
mvn javafx:run
```
4. Aplikacja uruchomi się automatycznie

> Przy pierwszym uruchomieniu Maven pobierze potrzebne biblioteki (wymaga internetu).  
> Kolejne uruchomienia nie wymagają połączenia z internetem.

### Dane aplikacji

Wszystkie dane są zapisywane lokalnie w pliku `collectorstools.db`  
w folderze, z którego uruchamiasz aplikację.  
**Nie usuwaj tego pliku** — to Twoja baza danych.

---

## Funkcjonalności

### Dłużnicy
- Dodawanie, edycja i usuwanie dłużników
- Dane osobowe: imię, nazwisko, PESEL, nr dowodu, telefon, e-mail, adres
- Wyszukiwanie po imieniu i nazwisku
- Podgląd liczby kontaktów i złamanych deklaracji bezpośrednio na liście

### Sprawy / Długi
- Każdy dłużnik może mieć wiele spraw
- Dane sprawy: nr sprawy, wierzyciel, kwota całkowita, kwota pozostała, data, opis
- Podsumowanie pozostałego zadłużenia w oknie szczegółów dłużnika

### Deklaracje
- Rejestracja deklaracji spłaty: data obietnicy, kwota, powiązana sprawa
- Automatyczne oznaczanie przeterminowanych deklaracji jako **Złamana** przy starcie aplikacji
- Ręczne oznaczanie deklaracji jako **Dotrzymana** (z datą wpłaty) lub **Złamana** (z powodem)
- Historia wszystkich deklaracji z podziałem na statusy

### Historia kontaktów
- Rejestracja każdego kontaktu z dłużnikiem
- Typy kontaktu: Wizyta terenowa, Telefon, Pismo, E-mail, Inne
- Notatka z każdego kontaktu
- Licznik kontaktów widoczny na liście głównej

---

## Architektura projektu

```
src/main/java/com/collectorstools/
├── Main.java                          # Punkt startowy aplikacji
├── db/
│   └── DatabaseManager.java          # Połączenie z SQLite, inicjalizacja schematu
├── model/
│   ├── Debtor.java                    # Model dłużnika
│   ├── Debt.java                      # Model sprawy/długu
│   ├── Declaration.java               # Model deklaracji spłaty
│   ├── Interaction.java               # Model kontaktu z dłużnikiem
│   ├── ContactType.java               # Enum: typ kontaktu
│   └── DeclarationStatus.java         # Enum: status deklaracji
├── repository/
│   ├── DebtorRepository.java          # CRUD dłużników (SQLite)
│   ├── DebtRepository.java            # CRUD spraw
│   ├── DeclarationRepository.java     # CRUD deklaracji
│   └── InteractionRepository.java     # CRUD interakcji
├── service/
│   ├── DebtorService.java             # Logika biznesowa + walidacja dłużników
│   ├── DebtService.java               # Logika biznesowa spraw
│   ├── DeclarationService.java        # Logika deklaracji, wykrywanie przeterminowanych
│   └── InteractionService.java        # Logika interakcji
└── ui/
    ├── MainWindow.java                # Główne okno — lista dłużników
    ├── DebtorDetailWindow.java        # Pływające okno szczegółów dłużnika
    ├── DebtorFormDialog.java          # Formularz dodawania/edycji dłużnika
    ├── DebtFormDialog.java            # Formularz sprawy
    ├── DeclarationFormDialog.java     # Formularz deklaracji
    └── InteractionFormDialog.java     # Formularz kontaktu

src/main/resources/
└── styles.css                         # Style JavaFX (wygląd aplikacji)
```

### Technologie
| Technologia | Wersja | Zastosowanie |
|---|---|---|
| Java | 21 (LTS) | Język programowania |
| JavaFX | 21.0.2 | Interfejs graficzny (UI) |
| SQLite | 3.45.1 | Lokalna baza danych (plik .db) |
| Lombok | 1.18.32 | Redukcja boilerplate (gettery, settery, builder) |
| Maven | 3.x | Zarządzanie zależnościami i budowanie |

---

## Możliwa rozbudowa w przyszłości

Projekt jest przygotowany na rozbudowę. Poniżej propozycje kolejnych kroków:

### Praca wielostanowiskowa (wielu windykatorów na wspólnych danych)
Obecnie każdy windykator ma lokalną bazę SQLite.  
Aby przejść na model współdzielony:
1. Zastąp SQLite bazą **PostgreSQL** lub **MySQL** na wspólnym serwerze sieciowym
2. Zmień `DatabaseManager` — podmień URL połączenia JDBC na adres serwera
3. Warstwa repozytoriów (`repository/`) nie wymaga zmian — używa standardowego JDBC
4. Dodaj obsługę logowania (identyfikacja windykatora przy każdej akcji)

### Synchronizacja z zewnętrznym systemem windykacyjnym
Aby importować dane z systemu nadrzędnego:
1. Dodaj moduł `sync/` z klasą `ExternalSystemClient`
2. Pobieraj dane przez REST API lub plik CSV/XML
3. Mapuj dane na modele (`Debtor`, `Debt`) i zapisuj przez istniejące repozytoria
4. Wywołuj synchronizację przy starcie (`Main.java`) lub na żądanie użytkownika

### Eksport raportów
- Dodaj zależność `Apache POI` (Excel) lub `iText` (PDF) do `pom.xml`
- Stwórz klasę `ReportService` w warstwie `service/`
- Dodaj przycisk "Eksportuj" w `MainWindow`

### Powiadomienia o zbliżających się terminach deklaracji
- Dodaj w `DeclarationService` metodę `findUpcoming(int days)`
- Przy starcie aplikacji wyświetlaj alert z listą deklaracji kończących się w ciągu X dni

---

## Licencja

Projekt prywatny — wszelkie prawa zastrzeżone.