package com.collectorstools.service;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditOperation;
import com.collectorstools.model.Debtor;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.util.PeselValidator;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class DebtorService {

    private final DebtorRepository debtorRepository;
    private final DebtorAuditService auditService;
    private final AuditService auditLog;

    public DebtorService() {
        this.debtorRepository = new DebtorRepository();
        this.auditService = new DebtorAuditService();
        this.auditLog = new AuditService();
    }

    /**
     * Konstruktor bez dziennika audytowego — używany przez testy, które go nie weryfikują.
     */
    public DebtorService(DebtorRepository debtorRepository, DebtorAuditService auditService) {
        this(debtorRepository, auditService, AuditService.noOp());
    }

    public DebtorService(DebtorRepository debtorRepository, DebtorAuditService auditService,
                         AuditService auditLog) {
        this.debtorRepository = debtorRepository;
        this.auditService = auditService;
        this.auditLog = auditLog;
    }

    public Debtor save(Debtor debtor) {
        validate(debtor);
        try {
            return debtorRepository.save(debtor);
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać dłużnika", e);
        }
    }

    public void update(Debtor debtor) {
        validate(debtor);
        try {
            Debtor previous = debtorRepository.findById(debtor.getId()).orElse(null);
            debtorRepository.update(debtor);
            if (previous != null) auditService.recordChanges(previous, debtor);
        } catch (SQLException e) {
            throw TechnicalException.database("zaktualizować dłużnika", e);
        }
    }

    /**
     * Usuwa dłużnika wraz z całą jego historią (kaskada w bazie).
     *
     * <p>Wpis audytowy powstaje przed usunięciem i celowo **nie ma** klucza obcego
     * do dłużnika — inaczej kaskada skasowałaby również ślad po samym usunięciu,
     * czyli informację najbardziej potrzebną przy wyjaśnianiu braku danych.
     */
    public void delete(long id) {
        try {
            Debtor removed = debtorRepository.findById(id).orElse(null);
            debtorRepository.delete(id);

            if (removed != null) {
                auditLog.record(AuditEntity.DEBTOR, id, id, AuditOperation.DELETE, "Dłużnik",
                        removed.getFullName()
                                + (removed.getPesel() != null ? " (" + removed.getPesel() + ")" : ""),
                        null, null);
            }
        } catch (SQLException e) {
            throw TechnicalException.database("usunąć dłużnika", e);
        }
    }

    public Optional<Debtor> findById(long id) {
        try {
            return debtorRepository.findById(id);
        } catch (SQLException e) {
            throw TechnicalException.database("wyszukać dłużnika", e);
        }
    }

    public List<Debtor> findAll() {
        try {
            return debtorRepository.findAll();
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać listy dłużników", e);
        }
    }

    public long count() {
        try {
            return debtorRepository.count();
        } catch (SQLException e) {
            throw TechnicalException.database("policzyć dłużników", e);
        }
    }

    public List<String> findDistinctCities() {
        try {
            return debtorRepository.findDistinctCities();
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać listy miast", e);
        }
    }

    public List<Debtor> search(String phrase) {
        if (phrase == null || phrase.isBlank()) return findAll();
        try {
            return debtorRepository.findByName(phrase);
        } catch (SQLException e) {
            throw TechnicalException.database("wyszukać", e);
        }
    }

    private void validate(Debtor debtor) {
        if (debtor.getFirstName() == null || debtor.getFirstName().isBlank())
            throw new BusinessRuleException("Imię jest wymagane.");
        if (debtor.getLastName() == null || debtor.getLastName().isBlank())
            throw new BusinessRuleException("Nazwisko jest wymagane.");
        if (debtor.getPesel() != null && !debtor.getPesel().isBlank()) {
            if (!PeselValidator.isValid(debtor.getPesel().trim()))
                throw new BusinessRuleException("Nieprawidłowy PESEL (długość, cyfry lub suma kontrolna).");
        }
    }
}
