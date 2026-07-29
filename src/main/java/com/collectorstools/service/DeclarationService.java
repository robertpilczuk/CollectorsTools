package com.collectorstools.service;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditOperation;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.repository.DeclarationRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class DeclarationService {

    private final DeclarationRepository declarationRepository;
    private final AuditService auditService;

    public DeclarationService() {
        this.declarationRepository = new DeclarationRepository();
        this.auditService = new AuditService();
    }

    /**
     * Konstruktor bez audytu — używany przez testy, które go nie weryfikują.
     */
    public DeclarationService(DeclarationRepository declarationRepository) {
        this(declarationRepository, AuditService.noOp());
    }

    public DeclarationService(DeclarationRepository declarationRepository, AuditService auditService) {
        this.declarationRepository = declarationRepository;
        this.auditService = auditService;
    }

    /**
     * Odnotowuje zmianę stanu deklaracji w dzienniku audytowym.
     */
    private void auditStatusChange(Declaration declaration, DeclarationStatus previous, String reason) {
        auditService.recordChange(AuditEntity.DECLARATION, declaration.getId(),
                declaration.getDebtorId(), AuditOperation.DECLARATION_STATUS, "Stan deklaracji",
                previous != null ? previous.getDisplayName() : null,
                declaration.getStatus().getDisplayName(), reason);
    }

    /**
     * Wywoływać przy starcie aplikacji — oznacza przeterminowane deklaracje jako ZLAMANA.
     */
    public void refreshOverdue() {
        try {
            declarationRepository.refreshOverdue();
        } catch (SQLException e) {
            throw TechnicalException.database("odświeżyć statusów deklaracji", e);
        }
    }

    public Declaration save(Declaration declaration) {
        // Wartości domyślne przed walidacją — reguła "termin nie wcześniejszy
        // niż data utworzenia" musi mieć obie daty do porównania
        if (declaration.getStatus() == null) {
            declaration.setStatus(DeclarationStatus.PENDING);
        }
        if (declaration.getCreatedAt() == null) {
            declaration.setCreatedAt(LocalDate.now());
        }
        validate(declaration);
        try {
            return declarationRepository.save(declaration);
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać deklaracji", e);
        }
    }

    public void update(Declaration declaration) {
        validate(declaration);
        try {
            declarationRepository.update(declaration);
        } catch (SQLException e) {
            throw TechnicalException.database("zaktualizować deklaracji", e);
        }
    }

    public void delete(long id) {
        try {
            declarationRepository.delete(id);
        } catch (SQLException e) {
            throw TechnicalException.database("usunąć deklaracji", e);
        }
    }

    public Optional<Declaration> findById(long id) {
        try {
            return declarationRepository.findById(id);
        } catch (SQLException e) {
            throw TechnicalException.database("wyszukać deklaracji", e);
        }
    }

    public List<Declaration> findByDebtorId(long debtorId) {
        try {
            return declarationRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać deklaracji dłużnika", e);
        }
    }

    public List<Declaration> findByDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to))
            throw new BusinessRuleException("Data 'od' nie może być późniejsza niż data 'do'.");
        try {
            return declarationRepository.findByDateRange(from, to);
        } catch (SQLException e) {
            throw TechnicalException.database("przefiltrować deklaracji", e);
        }
    }

    /**
     * Oznacza deklarację jako dotrzymaną (wpłata nastąpiła).
     *
     * <p>Dozwolone także dla deklaracji złamanej — wpłata po terminie jest
     * w windykacji zjawiskiem codziennym. Powód złamania zostaje zachowany,
     * a fakt opóźnienia wynika z porównania daty wpłaty z terminem.
     */
    public void markAsFulfilled(long declarationId, LocalDate paymentDate) {
        findById(declarationId).ifPresent(d -> {
            if (d.getStatus() == DeclarationStatus.CANCELLED) {
                throw new BusinessRuleException(
                        "Deklaracja została anulowana — nie można zarejestrować do niej wpłaty.");
            }
            DeclarationStatus previous = d.getStatus();
            d.setStatus(DeclarationStatus.FULFILLED);
            d.setPaymentDate(paymentDate != null ? paymentDate : LocalDate.now());
            update(d);
            auditStatusChange(d, previous, null);
        });
    }

    /**
     * Oznacza deklarację jako złamaną z podaniem powodu.
     */
    public void markAsBroken(long declarationId, String reason) {
        findById(declarationId).ifPresent(d -> {
            DeclarationStatus previous = d.getStatus();
            d.setStatus(DeclarationStatus.BROKEN);
            d.setBrokenReason(reason);
            update(d);
            auditStatusChange(d, previous, reason);
        });
    }

    /**
     * Wycofuje deklarację z ewidencji.
     *
     * <p>Uzasadnienie jest obowiązkowe — anulowanie usuwa deklarację ze wskaźnika
     * skuteczności, więc bez powodu byłoby to furtką do poprawiania statystyk.
     * Deklaracji już rozliczonej anulować nie można.
     */
    public void markAsCancelled(long declarationId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Anulowanie deklaracji wymaga podania uzasadnienia.");
        }
        findById(declarationId).ifPresent(d -> {
            if (d.getStatus() == DeclarationStatus.FULFILLED
                    || d.getStatus() == DeclarationStatus.PARTIALLY_FULFILLED) {
                throw new BusinessRuleException(
                        "Nie można anulować deklaracji, do której zarejestrowano wpłatę.");
            }
            DeclarationStatus previous = d.getStatus();
            d.setStatus(DeclarationStatus.CANCELLED);
            d.setCancelledReason(reason);
            update(d);
            auditStatusChange(d, previous, reason);
        });
    }

    /**
     * Deklaracje OCZEKUJACE z terminem w ciągu najbliższych {@code days} dni (od dziś włącznie),
     * posortowane chronologicznie. Używane do powiadomienia przy starcie aplikacji.
     */
    public List<Declaration> findByStatus(DeclarationStatus status) {
        try {
            return declarationRepository.findByStatus(status);
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać deklaracji wg statusu", e);
        }
    }

    public long countByStatus(DeclarationStatus status) {
        try {
            return declarationRepository.countByStatus(status);
        } catch (SQLException e) {
            throw TechnicalException.database("policzyć deklaracji", e);
        }
    }

    public List<Declaration> findUpcoming(int days) {
        LocalDate today = LocalDate.now();
        return findByDateRange(today, today.plusDays(days)).stream()
                .filter(d -> d.getStatus() == DeclarationStatus.PENDING)
                .toList();
    }

    /**
     * Suma obiecanych kwot deklaracji dłużnika — pomija złamane i anulowane.
     *
     * <p>Odpowiada na pytanie "na ile realnie mogę liczyć", a nie "ile mi naobiecywano":
     * obietnica niedotrzymana albo wycofana nie jest wartością do prognozowania.
     */
    public BigDecimal sumByDebtorId(long debtorId) {
        return findByDebtorId(debtorId).stream()
                .filter(d -> d.getStatus() != DeclarationStatus.BROKEN
                        && d.getStatus() != DeclarationStatus.CANCELLED)
                .map(Declaration::getPromisedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Suma deklaracji w przedziale dat.
     */
    public BigDecimal sumByDateRange(LocalDate from, LocalDate to) {
        return findByDateRange(from, to).stream()
                .map(Declaration::getPromisedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Liczba złamanych deklaracji dla danego dłużnika.
     */
    public long countBrokenByDebtorId(long debtorId) {
        return findByDebtorId(debtorId).stream()
                .filter(Declaration::isBroken)
                .count();
    }

    private void validate(Declaration declaration) {
        if (declaration.getDebtorId() == null)
            throw new BusinessRuleException("Deklaracja musi być przypisana do dłużnika.");
        if (declaration.getPromisedDate() == null)
            throw new BusinessRuleException("Data deklaracji jest wymagana.");
        if (declaration.getPromisedAmount() == null || declaration.getPromisedAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessRuleException("Kwota deklaracji musi być większa od zera.");

        // Obietnica z terminem wcześniejszym niż moment jej złożenia jest sprzeczna
        // sama w sobie. Deklarację wpisywaną wstecz (wizyta sprzed kilku dni, import
        // danych historycznych) rejestruje się razem z faktyczną datą utworzenia.
        if (declaration.getCreatedAt() != null
                && declaration.getPromisedDate().isBefore(declaration.getCreatedAt())) {
            throw new BusinessRuleException(String.format(
                    "Termin deklaracji (%s) nie może być wcześniejszy niż data jej zarejestrowania (%s).",
                    declaration.getPromisedDate(), declaration.getCreatedAt()));
        }
    }
}
