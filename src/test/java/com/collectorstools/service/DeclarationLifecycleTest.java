package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.repository.DeclarationRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reguły przejść między stanami deklaracji wprowadzone razem z rozszerzonym
 * zestawem statusów.
 */
class DeclarationLifecycleTest {

    private Connection conn;
    private DeclarationRepository repository;
    private DeclarationService service;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new DeclarationRepository(conn);
        service = new DeclarationService(repository);
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    /**
     * Deklaracja z terminem w przeszłości musi mieć odpowiednio wcześniejszą datę
     * zarejestrowania — obietnica nie może mieć terminu sprzed momentu jej złożenia.
     */
    private Declaration save(LocalDate date, String amount, DeclarationStatus status) {
        LocalDate createdAt = date.isBefore(LocalDate.now()) ? date : LocalDate.now();
        return service.save(Declaration.builder()
                .debtorId(debtorId)
                .createdAt(createdAt)
                .promisedDate(date)
                .promisedAmount(new BigDecimal(amount))
                .status(status)
                .build());
    }

    @Test
    @DisplayName("Zapis uzupełnia datę utworzenia")
    void saveSetsCreatedAt() {
        Declaration saved = save(LocalDate.now().plusDays(7), "100", null);

        assertNotNull(saved.getCreatedAt());
        assertEquals(LocalDate.now(), saved.getCreatedAt());
        assertEquals(LocalDate.now(), service.findById(saved.getId()).orElseThrow().getCreatedAt());
    }

    @Test
    @DisplayName("Anulowanie bez uzasadnienia jest odrzucane")
    void cancellationRequiresReason() {
        Declaration saved = save(LocalDate.now().plusDays(7), "100", DeclarationStatus.PENDING);

        assertThrows(IllegalArgumentException.class, () -> service.markAsCancelled(saved.getId(), null));
        assertThrows(IllegalArgumentException.class, () -> service.markAsCancelled(saved.getId(), ""));
        assertThrows(IllegalArgumentException.class, () -> service.markAsCancelled(saved.getId(), "   "));

        assertEquals(DeclarationStatus.PENDING,
                service.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Anulowanie z uzasadnieniem zapisuje powód")
    void cancellationStoresReason() {
        Declaration saved = save(LocalDate.now().plusDays(7), "100", DeclarationStatus.PENDING);

        service.markAsCancelled(saved.getId(), "wpisana omyłkowo, dublet deklaracji");

        Declaration cancelled = service.findById(saved.getId()).orElseThrow();
        assertEquals(DeclarationStatus.CANCELLED, cancelled.getStatus());
        assertEquals("wpisana omyłkowo, dublet deklaracji", cancelled.getCancelledReason());
        assertEquals("wpisana omyłkowo, dublet deklaracji", cancelled.getReason());
    }

    @Test
    @DisplayName("Deklaracji rozliczonej nie można anulować")
    void cannotCancelSettledDeclaration() {
        Declaration saved = save(LocalDate.now(), "100", DeclarationStatus.PENDING);
        service.markAsFulfilled(saved.getId(), LocalDate.now());

        assertThrows(IllegalArgumentException.class,
                () -> service.markAsCancelled(saved.getId(), "próba wycofania"));
        assertEquals(DeclarationStatus.FULFILLED,
                service.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Do anulowanej deklaracji nie można zarejestrować wpłaty")
    void cannotPayCancelledDeclaration() {
        Declaration saved = save(LocalDate.now().plusDays(3), "100", DeclarationStatus.PENDING);
        service.markAsCancelled(saved.getId(), "sprawa przekazana do sądu");

        assertThrows(IllegalArgumentException.class,
                () -> service.markAsFulfilled(saved.getId(), LocalDate.now()));
    }

    @Test
    @DisplayName("Wpłata po terminie domyka złamaną deklarację i zachowuje powód złamania")
    void latePaymentClosesBrokenDeclarationAndKeepsReason() {
        Declaration saved = save(LocalDate.now().minusDays(10), "100", DeclarationStatus.PENDING);
        service.markAsBroken(saved.getId(), "nie zastano w domu");

        service.markAsFulfilled(saved.getId(), LocalDate.now());

        Declaration paid = service.findById(saved.getId()).orElseThrow();
        assertEquals(DeclarationStatus.FULFILLED, paid.getStatus());
        assertEquals("nie zastano w domu", paid.getBrokenReason(),
                "Historia złamania nie może zniknąć po spóźnionej wpłacie");
        assertTrue(paid.isPaidLate(), "Wpłata po terminie musi być rozpoznana");
    }

    @Test
    @DisplayName("Wpłata w terminie nie jest oznaczana jako spóźniona")
    void onTimePaymentIsNotLate() {
        Declaration saved = save(LocalDate.now().plusDays(5), "100", DeclarationStatus.PENDING);

        service.markAsFulfilled(saved.getId(), LocalDate.now());

        assertFalse(service.findById(saved.getId()).orElseThrow().isPaidLate());
    }

    @Test
    @DisplayName("Automatyczne łamanie omija stany częściowy i anulowany")
    void refreshOverdueSkipsPartialAndCancelled() throws Exception {
        Declaration pending = save(LocalDate.now().minusDays(1), "100", DeclarationStatus.PENDING);
        Declaration partial = save(LocalDate.now().minusDays(1), "200", DeclarationStatus.PARTIALLY_FULFILLED);
        Declaration cancelled = save(LocalDate.now().minusDays(1), "300", DeclarationStatus.CANCELLED);
        Declaration today = save(LocalDate.now(), "400", DeclarationStatus.PENDING);

        repository.refreshOverdue();

        assertEquals(DeclarationStatus.BROKEN, service.findById(pending.getId()).orElseThrow().getStatus());
        assertEquals(DeclarationStatus.PARTIALLY_FULFILLED,
                service.findById(partial.getId()).orElseThrow().getStatus(),
                "Częściowo opłacona nie może zostać złamana automatycznie");
        assertEquals(DeclarationStatus.CANCELLED,
                service.findById(cancelled.getId()).orElseThrow().getStatus());
        assertEquals(DeclarationStatus.PENDING,
                service.findById(today.getId()).orElseThrow().getStatus(),
                "Deklaracja z terminem na dziś ma cały dzień na wpłatę");
    }

    @Test
    @DisplayName("Suma deklaracji pomija złamane i anulowane")
    void sumExcludesBrokenAndCancelled() {
        save(LocalDate.now(), "100", DeclarationStatus.PENDING);
        save(LocalDate.now(), "200", DeclarationStatus.FULFILLED);
        save(LocalDate.now(), "400", DeclarationStatus.BROKEN);
        save(LocalDate.now(), "800", DeclarationStatus.CANCELLED);

        assertEquals(0, new BigDecimal("300").compareTo(service.sumByDebtorId(debtorId)));
    }
}
