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

### Walidacja

Reguły biznesowe egzekwuje **warstwa serwisu**, nie formularz. UI może dodatkowo
blokować przycisk zapisu dla wygody użytkownika, ale nigdy nie jest jedynym
miejscem walidacji — inaczej import CSV i przyszłe API ominą regułę.

### Dodawanie nowej tabeli do bazy

1. `CREATE TABLE IF NOT EXISTS` w `DatabaseManager.initSchema()`
2. To samo `CREATE TABLE` w `DatabaseTestHelper.createTestConnection()`
3. Model → Repozytorium → Serwis → UI → Testy

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
