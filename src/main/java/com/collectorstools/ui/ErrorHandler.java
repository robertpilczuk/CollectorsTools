package com.collectorstools.ui;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Jedno miejsce, przez które błędy trafiają do użytkownika i do logu.
 *
 * <p>Podział jest prosty: naruszenie reguły biznesowej to komunikat, który użytkownik
 * ma przeczytać i poprawić dane — trafia na ekran w całości, do logu tylko jako ostrzeżenie
 * bez stosu. Awaria techniczna dostaje zrozumiały komunikat na ekranie, a pełny stos
 * wywołań ląduje w pliku logu, bo to on jest potrzebny przy diagnozie.
 *
 * <p>Wcześniej większość wywołań serwisów w interfejsie nie miała żadnego przechwytywania:
 * wyjątek na wątku JavaFX kończył się tym, że aplikacja pozornie nie robiła nic.
 */
public final class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private static final String LOG_HINT =
            "Szczegóły techniczne zapisano w pliku logs/collectorstools.log.";

    private ErrorHandler() {
    }

    /**
     * Wpina globalny przechwytywacz wyjątków. Bez niego błąd w obsłudze zdarzenia
     * kończy się wyłącznie wpisem w konsoli, której użytkownik nigdy nie widzi.
     */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                handle(error, "Nieoczekiwany błąd"));
        Platform.runLater(() -> Thread.currentThread().setUncaughtExceptionHandler(
                (thread, error) -> handle(error, "Nieoczekiwany błąd")));
    }

    /**
     * Pokazuje błąd użytkownikowi i zapisuje go w logu.
     *
     * @param context krótki opis operacji, przy której doszło do błędu
     */
    public static void handle(Throwable error, String context) {
        if (error instanceof BusinessRuleException business) {
            log.warn("Reguła biznesowa zablokowała operację [{}]: {}", context, business.getMessage());
            runOnFxThread(() -> showBusiness(context, business.getMessage()));
            return;
        }

        String userMessage = error instanceof TechnicalException technical
                ? technical.getUserMessage()
                : "Operacja nie została wykonana z powodu błędu aplikacji.";

        log.error("Błąd techniczny [{}]", context, error);
        runOnFxThread(() -> showTechnical(context, userMessage, error));
    }

    /**
     * Wykonuje operację, przechwytując błędy — skraca powtarzalne try/catch w oknach.
     *
     * @return {@code true}, jeśli operacja zakończyła się powodzeniem
     */
    public static boolean run(String context, Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (Throwable error) {
            handle(error, context);
            return false;
        }
    }

    private static void showBusiness(String context, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(context);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().setMinWidth(460);
        alert.showAndWait();
    }

    /**
     * Komunikat techniczny w układzie: co się stało → co zrobić dalej → gdzie są szczegóły.
     * Stos wywołań jest dostępny po rozwinięciu, ale domyślnie schowany.
     */
    private static void showTechnical(String context, String userMessage, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Błąd: " + context);
        alert.setHeaderText(userMessage);
        alert.setContentText(LOG_HINT);
        alert.getDialogPane().setMinWidth(520);

        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));

        TextArea details = new TextArea(stackTrace.toString());
        details.setEditable(false);
        details.setWrapText(false);
        details.setMaxWidth(Double.MAX_VALUE);
        details.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(details, Priority.ALWAYS);
        GridPane.setHgrow(details, Priority.ALWAYS);

        GridPane expandable = new GridPane();
        expandable.setMaxWidth(Double.MAX_VALUE);
        expandable.add(new Label("Szczegóły dla zgłoszenia:"), 0, 0);
        expandable.add(details, 0, 1);

        alert.getDialogPane().setExpandableContent(expandable);
        alert.showAndWait();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
