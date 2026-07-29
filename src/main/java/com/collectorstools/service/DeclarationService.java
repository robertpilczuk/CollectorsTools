package com.collectorstools.service;

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

    public DeclarationService() {
        this.declarationRepository = new DeclarationRepository();
    }

    public DeclarationService(DeclarationRepository declarationRepository) {
        this.declarationRepository = declarationRepository;
    }

    /**
     * Wywoływać przy starcie aplikacji — oznacza przeterminowane deklaracje jako ZLAMANA.
     */
    public void refreshOverdue() {
        try {
            declarationRepository.refreshOverdue();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd odświeżania statusów deklaracji: " + e.getMessage(), e);
        }
    }

    public Declaration save(Declaration declaration) {
        validate(declaration);
        if (declaration.getStatus() == null) {
            declaration.setStatus(DeclarationStatus.OCZEKUJACA);
        }
        try {
            return declarationRepository.save(declaration);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu deklaracji: " + e.getMessage(), e);
        }
    }

    public void update(Declaration declaration) {
        validate(declaration);
        try {
            declarationRepository.update(declaration);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd aktualizacji deklaracji: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try {
            declarationRepository.delete(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd usunięcia deklaracji: " + e.getMessage(), e);
        }
    }

    public Optional<Declaration> findById(long id) {
        try {
            return declarationRepository.findById(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd wyszukiwania deklaracji: " + e.getMessage(), e);
        }
    }

    public List<Declaration> findByDebtorId(long debtorId) {
        try {
            return declarationRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania deklaracji dłużnika: " + e.getMessage(), e);
        }
    }

    public List<Declaration> findByDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to))
            throw new IllegalArgumentException("Data 'od' nie może być późniejsza niż data 'do'.");
        try {
            return declarationRepository.findByDateRange(from, to);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd filtrowania deklaracji: " + e.getMessage(), e);
        }
    }

    /**
     * Oznacza deklarację jako dotrzymaną (wpłata nastąpiła).
     */
    public void markAsFulfilled(long declarationId, LocalDate paymentDate) {
        findById(declarationId).ifPresent(d -> {
            d.setStatus(DeclarationStatus.DOTRZYMANA);
            d.setPaymentDate(paymentDate != null ? paymentDate : LocalDate.now());
            update(d);
        });
    }

    /**
     * Oznacza deklarację jako złamaną z podaniem powodu.
     */
    public void markAsBroken(long declarationId, String reason) {
        findById(declarationId).ifPresent(d -> {
            d.setStatus(DeclarationStatus.ZLAMANA);
            d.setBrokenReason(reason);
            update(d);
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
            throw new RuntimeException("Błąd pobierania deklaracji wg statusu: " + e.getMessage(), e);
        }
    }

    public long countByStatus(DeclarationStatus status) {
        try {
            return declarationRepository.countByStatus(status);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd liczenia deklaracji: " + e.getMessage(), e);
        }
    }

    public List<Declaration> findUpcoming(int days) {
        LocalDate today = LocalDate.now();
        return findByDateRange(today, today.plusDays(days)).stream()
                .filter(d -> d.getStatus() == DeclarationStatus.OCZEKUJACA)
                .toList();
    }

    /**
     * Suma obiecanych kwot deklaracji dłużnika (tylko oczekujące i dotrzymane).
     */
    public BigDecimal sumByDebtorId(long debtorId) {
        return findByDebtorId(debtorId).stream()
                .filter(d -> d.getStatus() != DeclarationStatus.ZLAMANA)
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
            throw new IllegalArgumentException("Deklaracja musi być przypisana do dłużnika.");
        if (declaration.getPromisedDate() == null)
            throw new IllegalArgumentException("Data deklaracji jest wymagana.");
        if (declaration.getPromisedAmount() == null || declaration.getPromisedAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Kwota deklaracji musi być większa od zera.");
    }
}
