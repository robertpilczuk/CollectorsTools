# Budowanie i dystrybucja

Aplikacja wychodzi w dwóch postaciach: jednego pliku JAR do uruchomienia poleceniem
`java -jar` oraz paczki instalacyjnej z wbudowanym środowiskiem Javy, której odbiorca
nie musi niczego doinstalowywać.

---

## Wymagania

**JDK 21.** Lombok 1.18.32 nie kompiluje się na nowszym, a Maven z Homebrew ciągnie
własny, nowszy JDK i używa go domyślnie — build wywalał się wtedy błędem z wnętrza
Lomboka (`TypeTag :: UNKNOWN`), po którym nie widać, o co chodzi. Pilnuje tego
`maven-enforcer-plugin`: przy złej wersji build zatrzymuje się od razu z czytelnym
komunikatem.

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # macOS, Homebrew
```

## Fat JAR

```bash
mvn clean package
```

Powstają dwa pliki:

| Plik | Zawartość |
|---|---|
| `target/CollectorsTools-1.0-SNAPSHOT.jar` | same klasy projektu |
| `target/CollectorsTools-1.0-SNAPSHOT-all.jar` | klasy razem z zależnościami (~46 MB) |

Uruchomienie:

```bash
java -jar target/CollectorsTools-1.0-SNAPSHOT-all.jar
```

Klasą startową jest `com.collectorstools.Launcher`, a **nie** `Main`. Gdy klasa z manifestu
dziedziczy po `Application`, wyrzutnia Javy szuka modułów JavaFX na ścieżce modułów —
a w fat JAR-ze leżą one na zwykłej ścieżce klas. Uruchomienie kończy się wtedy
komunikatem „JavaFX runtime components are missing", mimo że w pliku jest wszystko,
czego potrzeba. Wejście przez klasę spoza hierarchii `Application` omija to sprawdzenie.

### Fat JAR jest związany z systemem

Zależności JavaFX rozwiązują się do wersji z natywnymi bibliotekami dla systemu, na którym
robiony jest build (`libglass.dylib`, `glass.dll`, `libglass.so`). JAR zbudowany na macOS
nie uruchomi się na Windowsie. Paczkę dla każdego systemu trzeba zbudować na tym systemie —
albo dołożyć zależności z jawnymi klasyfikatorami (`win`, `linux`, `mac-aarch64`).

### Ostrzeżenie przy starcie

```
WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module'
```

Spodziewane i nieszkodliwe: JavaFX ładuje się ze ścieżki klas, a nie jako moduł. Znika
dopiero po przejściu na pełną modularyzację (`module-info.java`), co dla aplikacji
rozprowadzanej przez `jpackage` nie daje nic poza brakiem tego napisu.

---

## Paczka instalacyjna — `jpackage`

`jpackage` wchodzi w skład JDK 21. Paczka niesie własne środowisko Javy, więc odbiorca nie
instaluje niczego poza samą aplikacją — kosztem rozmiaru (~205 MB na macOS).

Do katalogu wejściowego wkładamy **wyłącznie** fat JAR — `jpackage` kopiuje całą zawartość
`--input` do paczki, więc wskazanie `target/` zabrałoby ze sobą klasy, raporty i pliki
pośrednie:

```bash
mkdir -p build/wsad
cp target/CollectorsTools-1.0-SNAPSHOT-all.jar build/wsad/
```

### macOS

```bash
jpackage --type dmg \
  --name CollectorsTools --app-version 1.0 \
  --input build/wsad --main-jar CollectorsTools-1.0-SNAPSHOT-all.jar \
  --main-class com.collectorstools.Launcher \
  --dest dist
```

`--type app-image` daje samo `CollectorsTools.app` bez obrazu instalacyjnego — szybciej,
wygodniej do sprawdzenia. `--type dmg` wymaga narzędzi wiersza poleceń Xcode.

Paczka nie jest podpisana, więc przy pierwszym uruchomieniu Gatekeeper ją zablokuje.
Odbiorca otwiera ją przez menu kontekstowe → *Otwórz*, albo:

```bash
xattr -dr com.apple.quarantine /Applications/CollectorsTools.app
```

### Windows

```powershell
jpackage --type msi ^
  --name CollectorsTools --app-version 1.0 ^
  --input build\wsad --main-jar CollectorsTools-1.0-SNAPSHOT-all.jar ^
  --main-class com.collectorstools.Launcher ^
  --win-dir-chooser --win-menu --win-shortcut ^
  --dest dist
```

Wymaga [WiX Toolset 3.x](https://wixtoolset.org/) w `PATH`. Bez niego `jpackage` zrobi
tylko `--type app-image`.

### Linux

```bash
jpackage --type deb \
  --name collectorstools --app-version 1.0 \
  --input build/wsad --main-jar CollectorsTools-1.0-SNAPSHOT-all.jar \
  --main-class com.collectorstools.Launcher \
  --linux-shortcut \
  --dest dist
```

Wymaga `fakeroot` i `dpkg-deb` (`--type rpm` odpowiednio `rpmbuild`).

---

## Znane ograniczenie: baza i logi zapisują się w katalogu roboczym

`DatabaseManager` otwiera bazę pod ścieżką względną `collectorstools.db`, a Logback pisze
do `logs/`. Uruchomione z terminala trafiają do katalogu, z którego wywołano polecenie —
i tak działa to dziś poprawnie.

**Paczka uruchamiana z Findera czy menu Start ma jednak katalog roboczy ustawiony poza
kontrolą aplikacji** (na macOS `/`), więc baza powstanie w przypadkowym miejscu albo
w ogóle nie powstanie, jeśli katalog jest tylko do odczytu.

Docelowo dane użytkownika powinny iść do katalogu przewidzianego przez system:

| System | Katalog |
|---|---|
| macOS | `~/Library/Application Support/CollectorsTools` |
| Windows | `%APPDATA%\CollectorsTools` |
| Linux | `~/.local/share/CollectorsTools` |

Zmiana dotyka `DatabaseManager` (singleton) oraz konfiguracji Logbacka i czeka na decyzję.
Do tego czasu paczkę należy traktować jako demonstracyjną, a wersją do realnej pracy jest
`java -jar` uruchamiane z katalogu, w którym mają leżeć dane.
