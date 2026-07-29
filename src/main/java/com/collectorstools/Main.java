package com.collectorstools;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.service.DebtorService;
import com.collectorstools.service.DeclarationService;
import com.collectorstools.ui.ErrorHandler;
import com.collectorstools.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.util.List;

public class Main extends Application {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final int UPCOMING_DAYS = 7;

    @Override
    public void start(Stage primaryStage) {
        ErrorHandler.install();
        log.info("Uruchamianie CollectorsTools");

        // Odśwież statusy deklaracji przy starcie. Pierwsze sięgnięcie do bazy —
        // jeśli plik jest uszkodzony albo zajęty, wychodzi to właśnie tutaj.
        DeclarationService declarationService;
        try {
            declarationService = new DeclarationService();
            declarationService.refreshOverdue();
        } catch (RuntimeException e) {
            showStartupFailure(e);
            return;
        }

        MainWindow mainWindow = new MainWindow();
        Scene scene = new Scene(mainWindow.getRoot(), 1200, 750);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        primaryStage.setTitle("CollectorsTools");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();

        showUpcomingDeadlines(declarationService);
    }

    /**
     * Awaria bazy przy starcie — jedyny błąd, po którym aplikacja nie ma jak działać.
     *
     * <p>Zamiast stosu wywołań w konsoli, której użytkownik nigdy nie zobaczy, pokazujemy
     * okno z przyczyną i konkretnym wyjściem: wskazaniem kopii zapasowej. Kopia zostaje
     * skopiowana na miejsce bieżącej bazy, a aplikacja prosi o ponowne uruchomienie —
     * połączenie i schemat są inicjalizowane raz, przy starcie.
     */
    private void showStartupFailure(RuntimeException error) {
        log.error("Nie udało się uruchomić aplikacji", error);

        String message = error instanceof TechnicalException technical
                ? technical.getUserMessage()
                : "Nie udało się otworzyć bazy danych.";

        ButtonType restore = new ButtonType("Przywróć z kopii…", ButtonBar.ButtonData.OK_DONE);
        ButtonType quit = new ButtonType("Zamknij", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.ERROR, message, restore, quit);
        alert.setTitle("CollectorsTools — problem z bazą danych");
        alert.setHeaderText("Aplikacja nie może wystartować");
        alert.getDialogPane().setMinWidth(560);

        boolean restoreChosen = alert.showAndWait().filter(restore::equals).isPresent();
        if (restoreChosen) {
            restoreFromBackup();
        }
        Platform.exit();
    }

    private void restoreFromBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Wskaż kopię zapasową bazy");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Baza SQLite", "*.db"));
        File backup = chooser.showOpenDialog(null);
        if (backup == null) {
            return;
        }

        try {
            Files.copy(backup.toPath(), Path.of(DatabaseManager.DB_FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Przywrócono bazę z kopii: {}", backup.getAbsolutePath());

            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    "Baza została przywrócona z kopii.\n\nUruchom aplikację ponownie.");
            done.setTitle("Przywrócono kopię");
            done.setHeaderText(null);
            done.showAndWait();
        } catch (Exception e) {
            log.error("Nie udało się przywrócić bazy z kopii", e);
            Alert failed = new Alert(Alert.AlertType.ERROR,
                    "Nie udało się skopiować pliku kopii zapasowej.\n\n"
                            + "Sprawdź, czy plik nie jest otwarty w innym programie "
                            + "i czy masz prawo zapisu w katalogu aplikacji.");
            failed.setTitle("Błąd przywracania");
            failed.setHeaderText(null);
            failed.showAndWait();
        }
    }

    /**
     * Powiadomienie o deklaracjach z terminem w ciągu najbliższych {@link #UPCOMING_DAYS} dni.
     */
    private void showUpcomingDeadlines(DeclarationService declarationService) {
        List<Declaration> upcoming = declarationService.findUpcoming(UPCOMING_DAYS);
        if (upcoming.isEmpty()) return;

        DebtorService debtorService = new DebtorService();
        StringBuilder sb = new StringBuilder();
        for (Declaration d : upcoming) {
            String name = debtorService.findById(d.getDebtorId())
                    .map(Debtor::getFullName)
                    .orElse("Nieznany dłużnik");
            sb.append("• ").append(name)
              .append(" — ").append(d.getPromisedAmount()).append(" PLN")
              .append(" (termin: ").append(d.getPromisedDate()).append(")\n");
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Zbliżające się terminy");
        alert.setHeaderText("Deklaracje z terminem w ciągu " + UPCOMING_DAYS + " dni (" + upcoming.size() + ")");
        alert.setContentText(sb.toString());
        alert.showAndWait();
    }

    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
