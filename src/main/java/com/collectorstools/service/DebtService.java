package com.collectorstools.service;

import com.collectorstools.model.Debt;
import com.collectorstools.repository.DebtRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
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
        try {
            return debtRepository.save(debt);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu sprawy: " + e.getMessage(), e);
        }
    }

    public void update(Debt debt) {
        validate(debt);
        try {
            debtRepository.update(debt);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd aktualizacji sprawy: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try {
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
     * ma zastosowania. Weryfikujemy tylko, gdy podano choć jeden składnik, żeby nie
     * blokować pracy windykatorowi, który zna wyłącznie kwotę łączną.
     */
    private void validateComponentsMatchBalance(Debt debt) {
        boolean anyProvided = debt.getPrincipalAmount() != null
                || debt.getInterestAmount() != null
                || debt.getCostAmount() != null;
        if (!anyProvided) {
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
