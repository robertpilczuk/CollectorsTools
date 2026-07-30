package com.collectorstools;

/**
 * Punkt wejścia dla wersji spakowanej do jednego pliku JAR.
 *
 * <p>Klasa istnieje po to, żeby <b>nie</b> dziedziczyć po {@code Application}. Gdy klasa
 * startowa z manifestu rozszerza {@code Application}, wyrzutnia Javy sprawdza, czy moduły
 * JavaFX są na ścieżce modułów — a w fat JAR-ze są na zwykłej ścieżce klas. Uruchomienie
 * kończy się wtedy komunikatem „JavaFX runtime components are missing", mimo że wszystko
 * potrzebne jest w pliku.
 *
 * <p>Wejście przez klasę spoza hierarchii {@code Application} omija to sprawdzenie:
 * {@code Application.launch} wywołane z jej wnętrza inicjalizuje toolkit samo.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
