package com.collectorstools.service;

import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import com.collectorstools.repository.DebtRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DebtService {

    private final DebtRepository debtRepository;

    public DebtService() {
        this.debtRepository = new DebtRepository();
    }

    public DebtService(DebtRepository debtRepository) {
        this.debtRepository = debtRepository;
    }

    public Debt save(Debt debt) {
        validate(debt);
        if (debt.getStatus() == null) {
            debt.setStatus(DebtStatus.ACTIVE);
        }
        try {
            return debtRepository.save(debt);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu sprawy: " + e.getMessage(), e);
        }
    }

    /**
     * Zamyka sprawę w podanym stanie końcowym.
     *
     * <p>Zamknięcie sprawy z dodatnim saldem wymaga uzasadnienia — umorzenie,
     * przedawnienie czy przekazanie do sądu to decyzje, za które ktoś odpowiada,
     * a nie techniczne odhaczenie pozycji na liście. Bez tego wymogu najprostszym
     * sposobem na poprawienie statystyk byłoby zamykanie trudnych spraw.
     *
     * <p>Jako spłaconą można zamknąć wyłącznie sprawę z saldem zerowym.
     */
    public void closeCase(long debtId, DebtStatus status, String reason) {
        if (status == null || !status.isClosed()) {
            throw new IllegalArgumentException("Wskaż stan, w jakim sprawa ma zostać zamknięta.");
        }

        Debt debt = findById(debtId).orElseThrow(
                () -> new IllegalArgumentException("Nie znaleziono sprawy do zamknięcia."));

        if (debt.isClosed()) {
            throw new IllegalArgumentException("Sprawa jest już zamknięta ("
                    + debt.getStatus().getDisplayName() + ").");
        }

        boolean hasBalance = debt.getRemainingAmount() != null
                && debt.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0;

        if (status.requiresZeroBalance() && hasBalance) {
            throw new IllegalArgumentException(
                    "Sprawy z niespłaconym saldem nie można zamknąć jako spłaconej. "
                            + "Wybierz inną przyczynę zamknięcia.");
        }
        if (hasBalance && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "Zamknięcie sprawy z niespłaconym saldem wymaga podania przyczyny.");
        }

        debt.setStatus(status);
        debt.setClosedAt(LocalDate.now());
        debt.setCloseReason(reason != null && !reason.isBlank() ? reason.trim() : null);
        update(debt);
    }

    /**
     * Przywraca sprawę do obsługi — na przykład po tym, jak zamknięto ją omyłkowo
     * albo gdy dłużnik odezwał się po umorzeniu.
     */
    public void reopenCase(long debtId) {
        Debt debt = findById(debtId).orElseThrow(
                () -> new IllegalArgumentException("Nie znaleziono sprawy."));

        if (!debt.isClosed()) {
            throw new IllegalArgumentException("Sprawa jest już aktywna.");
        }

        debt.setStatus(DebtStatus.ACTIVE);
        debt.setClosedAt(null);
        debt.setCloseReason(null);
        update(debt);
    }

    public void update(Debt debt) {
        validate(debt);
        try {
            debtRepository.update(debt);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd aktualizacji sprawy: " + e.getMessage(), e);
        }
    }

    /**
     * Usuwa sprawę.
     *
     * <p>Sprawa z zarejestrowanymi wpłatami jest nieusuwalna: skasowanie jej zabrałoby
     * ze sobą historię finansową, której nie da się odtworzyć. Sprawę zakończoną
     * zamyka się, a nie kasuje.
     */
    public void delete(long id) {
        try {
            if (debtRepository.hasPayments(id)) {
                throw new IllegalArgumentException(
                        "Nie można usunąć sprawy, do której zarejestrowano wpłaty. "
                                + "Zamknij ją zamiast usuwać — historia wpłat musi zostać zachowana.");
            }
            debtRepository.delete(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd usunięcia sprawy: " + e.getMessage(), e);
        }
    }

    public Optional<Debt> findById(long id) {
        try {
            return debtRepository.findById(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd wyszukiwania sprawy: " + e.getMessage(), e);
        }
    }

    public List<Debt> findByDebtorId(long debtorId) {
        try {
            return debtRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania spraw dłużnika: " + e.getMessage(), e);
        }
    }

    public BigDecimal sumAllRemaining() {
        try {
            return debtRepository.sumAllRemaining();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd sumowania długów: " + e.getMessage(), e);
        }
    }

    public Set<Long> findDebtorIdsByCreditor(String phrase) {
        try {
            return debtRepository.findDebtorIdsByCreditor(phrase);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd filtrowania po wierzycielu: " + e.getMessage(), e);
        }
    }

    public BigDecimal getTotalRemainingForDebtor(long debtorId) {
        return findByDebtorId(debtorId).stream()
                .map(Debt::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Dopuszczalna różnica przy porównywaniu składników salda — jeden grosz. */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private void validate(Debt debt) {
        if (debt.getCaseNumber() == null || debt.getCaseNumber().isBlank())
            throw new IllegalArgumentException("Numer sprawy jest wymagany.");
        if (debt.getCreditor() == null || debt.getCreditor().isBlank())
            throw new IllegalArgumentException("Wierzyciel jest wymagany.");
        if (debt.getTotalAmount() == null || debt.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Kwota długu musi być większa od zera.");
        if (debt.getRemainingAmount() == null || debt.getRemainingAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Pozostała kwota nie może być ujemna.");

        validateNotNegative(debt.getPrincipalAmount(), "Kapitał");
        validateNotNegative(debt.getInterestAmount(), "Odsetki");
        validateNotNegative(debt.getCostAmount(), "Koszty");
        validateComponentsMatchBalance(debt);
    }

    private void validateNotNegative(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + " nie może być ujemny.");
        }
    }

    /**
     * Saldo musi odpowiadać sumie kapitału, odsetek i kosztów.
     *
     * <p>Niezmiennik potwierdzony na eksporcie wierzyciela — zgadzał się co do grosza
     * we wszystkich 171 sprawach. Sprawdzenie porównuje z tolerancją grosza, bo kwoty
     * przychodzą z arkuszy jako liczby zmiennoprzecinkowe.
     *
     * <p>Sprawa wprowadzona ręcznie może nie mieć rozbicia salda — wtedy reguła nie
     * ma zastosowania. Za "brak rozbicia" uznajemy zerową sumę składników, a nie ich
     * nieustawienie: kolumny w bazie są NOT NULL DEFAULT 0, więc sprawa zapisana bez
     * rozbicia wraca z odczytu z zerami, nie z wartościami pustymi. Rozpoznawanie po
     * {@code null} działałoby wyłącznie do pierwszego odczytu z bazy.
     *
     * <p>Ten sam warunek stosuje {@code PaymentService} przy dzieleniu wpłaty między
     * składniki — obie reguły muszą widzieć "brak rozbicia" tak samo.
     */
    private void validateComponentsMatchBalance(Debt debt) {
        if (debt.getComponentsSum().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        BigDecimal difference = debt.getComponentsSum().subtract(debt.getRemainingAmount()).abs();
        if (difference.compareTo(AMOUNT_TOLERANCE) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Rozbicie salda nie zgadza się z kwotą pozostałą: %s + %s + %s = %s, oczekiwano %s.",
                    orZero(debt.getPrincipalAmount()), orZero(debt.getInterestAmount()),
                    orZero(debt.getCostAmount()), debt.getComponentsSum(), debt.getRemainingAmount()));
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
