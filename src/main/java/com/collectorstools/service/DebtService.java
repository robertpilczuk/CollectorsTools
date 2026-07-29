package com.collectorstools.service;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditOperation;
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
    private final AuditService auditService;

    public DebtService() {
        this.debtRepository = new DebtRepository();
        this.auditService = new AuditService();
    }

    /**
     * Konstruktor bez audytu — używany przez testy, które go nie weryfikują.
     * Ścieżka produkcyjna zawsze przechodzi przez konstruktor bezargumentowy.
     */
    public DebtService(DebtRepository debtRepository) {
        this(debtRepository, AuditService.noOp());
    }

    public DebtService(DebtRepository debtRepository, AuditService auditService) {
        this.debtRepository = debtRepository;
        this.auditService = auditService;
    }

    public Debt save(Debt debt) {
        validate(debt);
        if (debt.getStatus() == null) {
            debt.setStatus(DebtStatus.ACTIVE);
        }
        try {
            return debtRepository.save(debt);
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać sprawy", e);
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
            throw new BusinessRuleException("Wskaż stan, w jakim sprawa ma zostać zamknięta.");
        }

        Debt debt = findById(debtId).orElseThrow(
                () -> new BusinessRuleException("Nie znaleziono sprawy do zamknięcia."));

        if (debt.isClosed()) {
            throw new BusinessRuleException("Sprawa jest już zamknięta ("
                    + debt.getStatus().getDisplayName() + ").");
        }

        boolean hasBalance = debt.getRemainingAmount() != null
                && debt.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0;

        if (status.requiresZeroBalance() && hasBalance) {
            throw new BusinessRuleException(
                    "Sprawy z niespłaconym saldem nie można zamknąć jako spłaconej. "
                            + "Wybierz inną przyczynę zamknięcia.");
        }
        if (hasBalance && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException(
                    "Zamknięcie sprawy z niespłaconym saldem wymaga podania przyczyny.");
        }

        DebtStatus previous = debt.getStatus();
        debt.setStatus(status);
        debt.setClosedAt(LocalDate.now());
        debt.setCloseReason(reason != null && !reason.isBlank() ? reason.trim() : null);
        update(debt);

        auditService.record(AuditEntity.DEBT, debt.getId(), debt.getDebtorId(),
                AuditOperation.CASE_CLOSED, "Stan sprawy",
                previous != null ? previous.getDisplayName() : null,
                status.getDisplayName(), debt.getCloseReason());
    }

    /**
     * Przywraca sprawę do obsługi — na przykład po tym, jak zamknięto ją omyłkowo
     * albo gdy dłużnik odezwał się po umorzeniu.
     */
    public void reopenCase(long debtId) {
        Debt debt = findById(debtId).orElseThrow(
                () -> new BusinessRuleException("Nie znaleziono sprawy."));

        if (!debt.isClosed()) {
            throw new BusinessRuleException("Sprawa jest już aktywna.");
        }

        DebtStatus previous = debt.getStatus();
        debt.setStatus(DebtStatus.ACTIVE);
        debt.setClosedAt(null);
        debt.setCloseReason(null);
        update(debt);

        auditService.record(AuditEntity.DEBT, debt.getId(), debt.getDebtorId(),
                AuditOperation.CASE_REOPENED, "Stan sprawy",
                previous.getDisplayName(), DebtStatus.ACTIVE.getDisplayName(), null);
    }

    public void update(Debt debt) {
        validate(debt);
        try {
            debtRepository.update(debt);
        } catch (SQLException e) {
            throw TechnicalException.database("zaktualizować sprawy", e);
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
                throw new BusinessRuleException(
                        "Nie można usunąć sprawy, do której zarejestrowano wpłaty. "
                                + "Zamknij ją zamiast usuwać — historia wpłat musi zostać zachowana.");
            }
            // Opis sprawy trzeba odczytać przed usunięciem — potem nie będzie już czego opisać
            Debt removed = debtRepository.findById(id).orElse(null);
            debtRepository.delete(id);

            if (removed != null) {
                auditService.record(AuditEntity.DEBT, id, removed.getDebtorId(),
                        AuditOperation.DELETE, "Sprawa",
                        removed.getCaseNumber() + " — " + removed.getCreditor(), null, null);
            }
        } catch (SQLException e) {
            throw TechnicalException.database("usunąć sprawy", e);
        }
    }

    public Optional<Debt> findById(long id) {
        try {
            return debtRepository.findById(id);
        } catch (SQLException e) {
            throw TechnicalException.database("wyszukać sprawy", e);
        }
    }

    public List<Debt> findByDebtorId(long debtorId) {
        try {
            return debtRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać spraw dłużnika", e);
        }
    }

    public BigDecimal sumAllRemaining() {
        try {
            return debtRepository.sumAllRemaining();
        } catch (SQLException e) {
            throw TechnicalException.database("zsumować długów", e);
        }
    }

    public Set<Long> findDebtorIdsByCreditor(String phrase) {
        try {
            return debtRepository.findDebtorIdsByCreditor(phrase);
        } catch (SQLException e) {
            throw TechnicalException.database("przefiltrować po wierzycielu", e);
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
            throw new BusinessRuleException("Numer sprawy jest wymagany.");
        if (debt.getCreditor() == null || debt.getCreditor().isBlank())
            throw new BusinessRuleException("Wierzyciel jest wymagany.");
        if (debt.getTotalAmount() == null || debt.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessRuleException("Kwota długu musi być większa od zera.");
        if (debt.getRemainingAmount() == null || debt.getRemainingAmount().compareTo(BigDecimal.ZERO) < 0)
            throw new BusinessRuleException("Pozostała kwota nie może być ujemna.");

        validateNotNegative(debt.getPrincipalAmount(), "Kapitał");
        validateNotNegative(debt.getInterestAmount(), "Odsetki");
        validateNotNegative(debt.getCostAmount(), "Koszty");
        validateComponentsMatchBalance(debt);
    }

    private void validateNotNegative(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException(label + " nie może być ujemny.");
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
            throw new BusinessRuleException(String.format(
                    "Rozbicie salda nie zgadza się z kwotą pozostałą: %s + %s + %s = %s, oczekiwano %s.",
                    orZero(debt.getPrincipalAmount()), orZero(debt.getInterestAmount()),
                    orZero(debt.getCostAmount()), debt.getComponentsSum(), debt.getRemainingAmount()));
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
