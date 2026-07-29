package com.collectorstools.ui;

import javafx.application.Platform;
import org.testfx.api.FxToolkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Minimalne wsparcie do testowania okien JavaFX bez sterowania myszą i klawiaturą.
 *
 * <p>Testy dialogów sprawdzają ich logikę — stan przycisku zapisu, zawartość list,
 * wynik zwracany przez formularz — a nie wygląd. Wystarczy do tego uruchomiony
 * toolkit JavaFX i możliwość wykonania kodu na wątku aplikacji; klikanie robotem
 * byłoby wolniejsze i znacznie bardziej kruche.
 */
public final class FxTestSupport {

    private static final int TIMEOUT_SECONDS = 15;

    private FxTestSupport() {
    }

    /**
     * Uruchamia toolkit JavaFX. Wielokrotne wywołanie jest bezpieczne.
     */
    public static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    /**
     * Wykonuje kod na wątku aplikacji JavaFX i zwraca jego wynik.
     *
     * <p>Wyjątki z wątku JavaFX są przenoszone do wątku testu — inaczej test
     * przechodziłby mimo błędu, który w aplikacji objawiłby się zamrożonym oknem.
     */
    public static <T> T onFxThread(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static void onFxThread(Runnable action) throws Exception {
        onFxThread(() -> {
            action.run();
            return null;
        });
    }
}
