package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.util.PeselValidator;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class DebtorService {

    private final DebtorRepository debtorRepository;
    private final DebtorAuditService auditService;

    public DebtorService() {
        this.debtorRepository = new DebtorRepository();
        this.auditService = new DebtorAuditService();
    }

    public DebtorService(DebtorRepository debtorRepository, DebtorAuditService auditService) {
        this.debtorRepository = debtorRepository;
        this.auditService = auditService;
    }

    public Debtor save(Debtor debtor) {
        validate(debtor);
        try {
            return debtorRepository.save(debtor);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu dłużnika: " + e.getMessage(), e);
        }
    }

    public void update(Debtor debtor) {
        validate(debtor);
        try {
            Debtor previous = debtorRepository.findById(debtor.getId()).orElse(null);
            debtorRepository.update(debtor);
            if (previous != null) auditService.recordChanges(previous, debtor);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd aktualizacji dłużnika: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try {
            debtorRepository.delete(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd usunięcia dłużnika: " + e.getMessage(), e);
        }
    }

    public Optional<Debtor> findById(long id) {
        try {
            return debtorRepository.findById(id);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd wyszukiwania dłużnika: " + e.getMessage(), e);
        }
    }

    public List<Debtor> findAll() {
        try {
            return debtorRepository.findAll();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania listy dłużników: " + e.getMessage(), e);
        }
    }

    public long count() {
        try {
            return debtorRepository.count();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd liczenia dłużników: " + e.getMessage(), e);
        }
    }

    public List<String> findDistinctCities() {
        try {
            return debtorRepository.findDistinctCities();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania listy miast: " + e.getMessage(), e);
        }
    }

    public List<Debtor> search(String phrase) {
        if (phrase == null || phrase.isBlank()) return findAll();
        try {
            return debtorRepository.findByName(phrase);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd wyszukiwania: " + e.getMessage(), e);
        }
    }

    private void validate(Debtor debtor) {
        if (debtor.getFirstName() == null || debtor.getFirstName().isBlank())
            throw new IllegalArgumentException("Imię jest wymagane.");
        if (debtor.getLastName() == null || debtor.getLastName().isBlank())
            throw new IllegalArgumentException("Nazwisko jest wymagane.");
        if (debtor.getPesel() != null && !debtor.getPesel().isBlank()) {
            if (!PeselValidator.isValid(debtor.getPesel().trim()))
                throw new IllegalArgumentException("Nieprawidłowy PESEL (długość, cyfry lub suma kontrolna).");
        }
    }
}
