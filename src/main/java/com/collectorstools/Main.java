package com.collectorstools;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.service.DebtorService;
import com.collectorstools.service.DeclarationService;
import com.collectorstools.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.List;

public class Main extends Application {

    private static final int UPCOMING_DAYS = 7;

    @Override
    public void start(Stage primaryStage) {
        // Odśwież statusy deklaracji przy starcie
        DeclarationService declarationService = new DeclarationService();
        declarationService.refreshOverdue();

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
