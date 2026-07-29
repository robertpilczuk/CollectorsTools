package com.collectorstools.service;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.DebtorPhone;
import com.collectorstools.repository.DebtorPhoneRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public class DebtorPhoneService {

    private final DebtorPhoneRepository phoneRepository;

    public DebtorPhoneService() {
        this.phoneRepository = new DebtorPhoneRepository();
    }

    public DebtorPhoneService(DebtorPhoneRepository phoneRepository) {
        this.phoneRepository = phoneRepository;
    }

    public DebtorPhone save(DebtorPhone phone) {
        validate(phone);
        try {
            return phoneRepository.save(phone);
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać numeru telefonu", e);
        }
    }

    /**
     * Dodaje numer, pomijając duplikaty. Import wielokrotnie napotyka ten sam numer
     * przy kolejnych sprawach tego samego dłużnika — powtórzenie nie jest błędem,
     * po prostu nie wnosi nic nowego.
     *
     * @return {@code true}, jeśli numer został faktycznie dodany
     */
    public boolean addIfAbsent(DebtorPhone phone) {
        validate(phone);
        try {
            if (phoneRepository.exists(phone.getDebtorId(), phone.getPhoneNumber())) {
                return false;
            }
            phoneRepository.save(phone);
            return true;
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać numeru telefonu", e);
        }
    }

    public List<DebtorPhone> findByDebtorId(long debtorId) {
        try {
            return phoneRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać numerów telefonu", e);
        }
    }

    public Set<Long> findDebtorIdsByPhone(String phrase) {
        try {
            return phoneRepository.findDebtorIdsByPhone(phrase);
        } catch (SQLException e) {
            throw TechnicalException.database("wyszukać po telefonie", e);
        }
    }

    public void delete(long id) {
        try {
            phoneRepository.delete(id);
        } catch (SQLException e) {
            throw TechnicalException.database("usunąć numeru telefonu", e);
        }
    }

    private void validate(DebtorPhone phone) {
        if (phone.getDebtorId() == null)
            throw new BusinessRuleException("Numer telefonu musi być przypisany do dłużnika.");
        if (phone.getPhoneNumber() == null || phone.getPhoneNumber().isBlank())
            throw new BusinessRuleException("Numer telefonu nie może być pusty.");
    }
}
