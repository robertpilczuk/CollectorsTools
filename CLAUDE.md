# CLAUDE.md — konwencje kodu CollectorsTools

Dokument opisuje standardy obowiązujące w tym repozytorium. Każda zmiana w kodzie
ma być z nimi zgodna.

## Stack

- **Java 21**, **JavaFX 21.0.2**, **SQLite** (`collectorstools.db`)
- **Lombok** (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`)
- **Maven**, **JUnit 5**

## Struktura projektu

```
src/main/java/com/collectorstools/
├── Main.java
├── db/           DatabaseManager (singleton, SQLite)
├── model/        Debtor, Debt, Declaration, Interaction, enums
├── repository/   CRUD — każde repo ma dwa konstruktory (produkcyjny + testowy z Connection)
├── service/      Logika biznesowa + walidacja — każdy serwis ma dwa konstruktory
└── ui/           JavaFX — MainWindow, DebtorDetailWindow, dialogi formularzy

src/main/resources/
└── styles.css

src/test/java/com/collectorstools/
├── util/         DatabaseTestHelper (in-memory SQLite)
├── repository/   *RepositoryTest
└── service/      *ServiceTest
```

Warstwy są rozdzielone ściśle: UI nie sięga do bazy, repozytoria nie zawierają
logiki biznesowej, serwisy nie znają JavaFX.

## Konwencje kodu

### Repozytoria — zawsze dwa konstruktory

Konstruktor bezargumentowy do produkcji, konstruktor z `Connection` do testów
na bazie in-memory. Żadnego z nich nie wolno usuwać.

```java
public class XxxRepository {
    private final Connection conn;

    public XxxRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public XxxRepository(Connection conn) {
        this.conn = conn;
    }
}
```

### Serwisy — zawsze dwa konstruktory

Analogicznie: bezargumentowy do produkcji, z wstrzykniętymi zależnościami do testów.

```java
public class XxxService {
    private final XxxRepository xxxRepository;

    public XxxService() {
        this.xxxRepository = new XxxRepository();
    }

    public XxxService(XxxRepository xxxRepository) {
        this.xxxRepository = xxxRepository;
    }
}
```

### Modele — Lombok

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Xxx { ... }
```

### UI — JavaFX Dialog

- Zawsze `setPrefWidth()` i `setMinWidth()` — minimum 480px
- Kolumny `GridPane` przez `ColumnConstraints(140)` — nie `setPrefWidth()` na polach
- Etykiety z `setMinWidth(130)`
- CSS ładuj z obsługą błędu (`try/catch` na `getResource`)

### Operacje wielotabelowe — zawsze w transakcji

Każda operacja biznesowa zapisująca do więcej niż jednej tabeli musi przejść
przez `TransactionManager`. Inaczej przerwanie w połowie zostawia bazę
w stanie niespójnym — np. wpłatę bez zaktualizowanego salda sprawy.

```java
transactionManager.execute(() -> {
    paymentRepository.save(payment);
    debtRepository.updateRemainingAmount(debtId, newBalance);
});
```

Wywołanie zagnieżdżone dołącza do transakcji zewnętrznej — serwis może
bezpiecznie wołać inny serwis, który również używa transakcji.

### Walidacja

Reguły biznesowe egzekwuje **warstwa serwisu**, nie formularz. UI może dodatkowo
blokować przycisk zapisu dla wygody użytkownika, ale nigdy nie jest jedynym
miejscem walidacji — inaczej import CSV i przyszłe API ominą regułę.

### Zmiany w schemacie bazy

Schemat opisują wyłącznie pliki migracji w `src/main/resources/db/migrations`.
Nie wolno definiować tabel w kodzie Javy — ten sam zestaw migracji stosują
aplikacja i testy, więc powielenie definicji rozjechałoby oba schematy.

1. Utwórz `V{n}__opis.sql` z kolejnym numerem
2. Dopisz nazwę pliku na końcu tablicy `MIGRATIONS` w `SchemaManager`
3. Model → Repozytorium → Serwis → UI → Testy

Migracji już wydanej nigdy nie modyfikuj — bazy użytkowników mają ją
zastosowaną i nie zobaczą zmiany. Zamiast tego dodaj kolejną migrację.

Wersję schematu przechowuje `PRAGMA user_version` w pliku bazy. Każda migracja
wykonuje się w transakcji: błąd cofa całość i zostawia bazę w poprzedniej wersji.

## Commity — Conventional Commits

```
typ(zakres): opis w czasie teraźniejszym, po angielsku
```

Typy: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `style`, `build`, `ci`

Przykłady:

```
feat(ui): add CSV export button to MainWindow
fix(repo): handle null debt_id in DeclarationRepository.update
refactor(model): extract AddressInfo value object from Debtor
test(service): add DeclarationService edge case tests
```

## Uruchamianie

```bash
mvn clean compile    # kompilacja
mvn javafx:run       # uruchomienie aplikacji
mvn test             # testy
```
